/*
 * Copyright © 2019, 2020, 2021, 2022, 2023 Peter Doornbosch
 *
 * This file is part of Flupke, a HTTP3 client Java library
 *
 * Flupke is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Flupke is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.http3.impl;

import net.luminis.http3.core.Http3ClientConnection;
import net.luminis.http3.server.HttpError;
import net.luminis.qpack.Decoder;
import net.luminis.qpack.Encoder;
import net.luminis.quic.QuicClientConnection;
import net.luminis.quic.QuicStream;
import net.luminis.quic.TransportParameters;
import net.luminis.quic.VariableLengthInteger;
import net.luminis.tls.util.ByteUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.*;
import java.net.ProtocolException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static net.luminis.http3.impl.SettingsFrame.SETTINGS_ENABLE_CONNECT_PROTOCOL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;


public class Http3ClientConnectionImplTest {

    private List<Map.Entry<String, String>> mockEncoderCompressedHeaders = new ArrayList<>();

    // An empty headers that is used in combination with the mockQuicConnectionWithStreams method.
    private byte[] MOCK_HEADER = new byte[] {
            // Partial response for Headers frame, to model aborted stream
            0x01, // type Headers Frame
            0x00, // payload length
    };

    @Test
    public void requestShouldContainCompulsaryPseudoHeaders() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost",4433, new NoOpEncoder());
        mockQuicConnectionWithStreams(http3Connection, MOCK_HEADER);

        HttpRequest request = HttpRequest.newBuilder().uri(new URI("https://www.example.com")).build();
        http3Connection.send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(mockEncoderCompressedHeaders)
                .contains(entry(":method", "GET"))
                .contains(entry(":scheme", "https"))
                .contains(entry(":path", "/"))
                .contains(entry(":authority", "www.example.com:443"));
    }

    @Test
    public void pathAndQueryPartsOfUriShouldBeAddedToPathPseudoHeader() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost",4433, new NoOpEncoder());
        mockQuicConnectionWithStreams(http3Connection, MOCK_HEADER);

        // When
        HttpRequest request = HttpRequest.newBuilder().uri(new URI("https://www.example.com/path/element?query=value&key=value")).build();
        http3Connection.send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(mockEncoderCompressedHeaders).contains(entry(":path", "/path/element?query=value&key=value"));
    }

    @Test
    public void headersFrameAuthorityHeaderShouldExludeUserInfo() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost",4433, new NoOpEncoder());
        mockQuicConnectionWithStreams(http3Connection, MOCK_HEADER);

        // When
        HttpRequest request = HttpRequest.newBuilder().uri(new URI("http://username:password@example.com:4433/index.html")).build();
        http3Connection.send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(mockEncoderCompressedHeaders).contains(entry(":authority", "example.com:4433"));
    }

    @Test
    public void testServerSettingsFrameIsProcessed() throws IOException {
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost",4433);

        InputStream serverControlInputStream = new ByteArrayInputStream(new byte[]{
                0x00,  // type: control stream
                0x04,  // frame type: SETTINGS
                0x04,  // payload length
                // frame payload
                0x01,  // identifier: SETTINGS_QPACK_MAX_TABLE_CAPACITY
                32,    // value
                0x07,  // identifier: SETTINGS_QPACK_BLOCKED_STREAMS
                16     // value
        });

        QuicStream mockedServerControlStream = mock(QuicStream.class);
        when(mockedServerControlStream.getInputStream()).thenReturn(serverControlInputStream);
        http3Connection.registerServerInitiatedStream(mockedServerControlStream);

        assertThat(http3Connection.getServerQpackMaxTableCapacity()).isEqualTo(32);
        assertThat(http3Connection.getServerQpackBlockedStreams()).isEqualTo(16);
    }

    @Test
    public void testClientSendsSettingsFrameOnControlStream() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        QuicClientConnection quicConnection = mock(QuicClientConnection.class);
        FieldSetter.setField(http3Connection, Http3ConnectionImpl.class.getDeclaredField("quicConnection"), quicConnection);

        QuicStream quicStreamMock = mock(QuicStream.class);
        ByteArrayOutputStream controlStreamOutput = new ByteArrayOutputStream();
        when(quicStreamMock.getOutputStream()).thenReturn(controlStreamOutput);
        when(quicConnection.createStream(anyBoolean())).thenReturn(quicStreamMock);
        http3Connection.connect(10);

        assertThat(controlStreamOutput.toByteArray()).isEqualTo(new byte[] {
                0x00,  // type: control stream
                0x04,  // frame type: SETTINGS
                0x04,  // payload length
                // frame payload
                0x01,  // identifier: SETTINGS_QPACK_MAX_TABLE_CAPACITY
                0,     // value
                0x07,  // identifier: SETTINGS_QPACK_BLOCKED_STREAMS
                0      // value
        });
    }

    @Test
    public void testReceiveHttpResponse() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[] {
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length (payload omitted for the test, is covered by the mock decoder)
                // Complete response for Data frame
                0x00, // type Data Frame
                0x05, // payload length
                0x4e, // 'N'
                0x69, // 'i'
                0x63, // 'c'
                0x65, // 'e'
                0x21, // '!'
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        HttpResponse<String> httpResponse = http3Connection.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(httpResponse.statusCode()).isEqualTo(200);
        assertThat(httpResponse.body()).isEqualTo("Nice!");
    }

    @Test
    public void receivingResponseWithInputStreamAndMultipleDataFramesShouldWork() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] frameDataLengthBytes = new byte[]{ 0x62, (byte) 0xc4 };  // Var-int encoded: 0x62c4 = 8900
        int dataPerFrame = VariableLengthInteger.parse(ByteBuffer.wrap(frameDataLengthBytes));
        int nrOfDataFrames = 2;
        byte[] responseBytes = new byte[2 + nrOfDataFrames * (3 + dataPerFrame)];
        // Partial response for Headers frame, the rest is covered by the mock decoder
        responseBytes[0] = 0x01; // type Headers Frame
        responseBytes[1] = 0x00; // payload length (payload omitted for the test, is covered by the mock decoder)
        // Complete response for 1st Data frame
        responseBytes[2] = 0x00; // type Data Frame
        responseBytes[3] = frameDataLengthBytes[0];
        responseBytes[4] = frameDataLengthBytes[1];
        int secondDataFrameStartIndex = 2 + 3 + dataPerFrame;
        Arrays.fill(responseBytes, 5, secondDataFrameStartIndex, (byte) 'a');
        // Complete response for 2nd Data frame
        responseBytes[secondDataFrameStartIndex] = 0x00; // type Data Frame
        responseBytes[secondDataFrameStartIndex + 1] = frameDataLengthBytes[0];
        responseBytes[secondDataFrameStartIndex + 2] = frameDataLengthBytes[1];
        Arrays.fill(responseBytes, secondDataFrameStartIndex + 3, secondDataFrameStartIndex + 3 + dataPerFrame, (byte) 'a');

        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost"))
                .build();

        // When
        HttpResponse<InputStream> httpResponse = http3Connection.send(request, HttpResponse.BodyHandlers.ofInputStream());

        // Then
        assertThat(httpResponse.statusCode()).isEqualTo(200);
        assertThat(httpResponse.body()).isNotNull();
        byte[] actual = httpResponse.body().readAllBytes();
        assertThat(actual).hasSize(nrOfDataFrames * dataPerFrame);
        assertThat(actual).containsOnly((byte) 'a');
    }

    @Test
    public void testMissingHeaderFrame() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[] {
                // Complete response for Data frame
                0x00, // type Data Frame
                0x05, // payload length
                0x4e, // 'N'
                0x69, // 'i'
                0x63, // 'c'
                0x65, // 'e'
                0x21, // '!'
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        assertThatThrownBy(
                () -> http3Connection.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("(quic stream");
    }

    @Test
    public void testResponseCanHaveTwoHeadersFrame() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[] {
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length (payload omitted for the test, is covered by the mock decoder
                // Complete response for Data frame
                0x00, // type Data Frame
                0x05, // payload length
                0x4e, // 'N'
                0x69, // 'i'
                0x63, // 'c'
                0x65, // 'e'
                0x21, // '!'
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length (payload omitted for the test, is covered by the mock decoder
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes, Map.of(":status", "200", "Content-Type", "crap"), Map.of("x-whatever", "true"), Map.of("header-number", "3"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        HttpResponse<String> httpResponse = http3Connection.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(httpResponse.statusCode()).isEqualTo(200);
        assertThat(httpResponse.body()).isEqualTo("Nice!");
        assertThat(httpResponse.headers().map()).containsKeys("Content-Type", "x-whatever");
    }

    @Test
    public void testStreamAbortedInHeadersFrame() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[]{
                // Partial response for Headers frame, to model aborted stream
                0x01, // type Headers Frame
                0x0f, // payload length
        };

        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        assertThatThrownBy(
                () -> http3Connection.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(EOFException.class);
    }

    @Test
    public void testStreamAbortedInDataFrame() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[] {
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length
                // Partial response for Data frame, to model aborted stream
                0x00, // type Data Frame
                0x05, // payload length
                0x4e, // 'N'
                0x69, // 'i'
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        assertThatThrownBy(
                () -> http3Connection.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(EOFException.class);
    }

    @Test
    public void noDataFrameMeansEmptyResponseBody() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[]{
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        HttpResponse<String> response = http3Connection.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEmpty();
    }

    @Test
    public void reservedLargeFrameTypeIsIgnored() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[] {
                // Reserved frame type: 3344129617399856696 (0x2E68BC2B47FB3E38) = 3344129617399856675 * 31 + 33
                (byte) (0x2E | 0xc0),  // Variable length integer: 8 bytes => most significant 2 bits are 1
                0x68,
                (byte) 0xBC,
                0x2B,
                0x47,
                (byte) 0xFB,
                0x3E,
                0x38,
                0x00, // Length 0
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length (payload omitted for the test, is covered by the mock decoder
                // Complete response for Data frame
                0x00, // type Data Frame
                0x05, // payload length
                0x4e, // 'N'
                0x69, // 'i'
                0x63, // 'c'
                0x65, // 'e'
                0x21, // '!'
        };
        mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .build();

        HttpResponse<String> httpResponse = http3Connection.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(httpResponse.statusCode()).isEqualTo(200);
        assertThat(httpResponse.body()).isEqualTo("Nice!");
    }

    @Test
    public void readFrameFromClosedStreamShouldReturnNull() throws Exception {
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        InputStream inputStream = new ByteArrayInputStream(new byte[]{ 0x01, 0x02, 0x03 });
        inputStream.read(new byte[3]);
        Http3Frame frame = http3Connection.readFrame(inputStream);
        assertThat(frame).isNull();
    }

    @Test
    public void postRequestEncodesRequestBodyInDataFrame() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        byte[] responseBytes = new byte[]{
                // Partial response for Headers frame, the rest is covered by the mock decoder
                0x01, // type Headers Frame
                0x00, // payload length
        };
        QuicStream http3Stream = mockQuicConnectionWithStreams(http3Connection, responseBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI("http://localhost"))
                .POST(HttpRequest.BodyPublishers.ofString("This is the request body."))
                .build();

        http3Connection.send(request, HttpResponse.BodyHandlers.ofString());

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(http3Stream.getOutputStream(), times(2)).write(captor.capture());
        byte[] dataFrameBytes = captor.getAllValues().get(1);
        assertThat(dataFrameBytes).endsWith("This is the request body.".getBytes());
    }

    @Test
    public void setupConnectOnlyOnce() throws Exception {
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);

        QuicClientConnection quicConnection = mockQuicConnection(http3Connection);
        when(quicConnection.isConnected()).thenReturn(false, true);  // Assuming (knowing) that Http3Connection.connect calls QuicConnection.isConnected once

        http3Connection.connect(10);
        http3Connection.connect(10);

        verify(quicConnection, times(1)).connect(anyInt(), anyString(), nullable(TransportParameters.class), anyList());
    }

    @Test
    public void sendConnectShouldSendConnect() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[] { 0x01, 0x00});
        Encoder mockedQPackEncoder = mock(Encoder.class);
        when(mockedQPackEncoder.compressHeaders(anyList())).thenReturn(ByteBuffer.allocate(0));
        FieldSetter.setField(http3Connection, Http3ClientConnectionImpl.class.getDeclaredField("qpackEncoder"), mockedQPackEncoder);

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        http3Connection.sendConnect(connectRequest);

        // Then
        ArgumentCaptor<List<Map.Entry<String, String>>> headersCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockedQPackEncoder).compressHeaders(headersCaptor.capture());
        Map<String, String> headers = headersCaptor.getValue().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(headers).containsAllEntriesOf(Map.of(":method", "CONNECT", ":authority", "proxy.net:443"));
    }

    @Test
    public void sendConnectShouldHandleEmptyResponse() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[0]);

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();

        // Then
        assertThatThrownBy(() -> http3Connection.sendConnect(connectRequest))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    public void sendConnectShouldThrowWhenStatusNot2xx() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[] { 0x01, 0x00 }, Map.of(":status", "404"));

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();

        // Then
        assertThatThrownBy(() -> http3Connection.sendConnect(connectRequest))
                .isInstanceOf(HttpError.class)
                .hasMessageContaining("404");
    }

    @Test
    public void httpStreamShouldCopyAllBytesIntoDataFrame() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        QuicStream quicStream = mockQuicConnectionWithStreams(http3Connection, new byte[]{ 0x01, 0x00 });

        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        Http3ClientConnectionImpl.HttpStreamImpl httpStream = http3Connection.sendConnect(connectRequest);
        clearInvocations(quicStream.getOutputStream());

        // When
        httpStream.getOutputStream().write("hello world".getBytes(StandardCharsets.UTF_8));

        // Then
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(quicStream.getOutputStream()).write(bytesCaptor.capture());
        byte[] sentData = bytesCaptor.getValue();
        assertThat(sentData[0]).isEqualTo((byte) 0x00); // DataFrame Type
        assertThat(sentData[1]).isEqualTo((byte) 11);   // Length
        assertThat(new String(Arrays.copyOfRange(sentData, 2, 13))).isEqualTo("hello world");
    }

    @Test
    public void httpStreamShouldCopyBytesFromDataFrame() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        //                                       Header Frame  Data Frame       Data Frame  Data Frame
        byte[] inputData = ByteUtils.hexToBytes("01 00         00 05 68656c6c6f 00 01 20    00 05 776f726c64");
        mockQuicConnectionWithStreams(http3Connection, inputData);

        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        Http3ClientConnectionImpl.HttpStreamImpl httpStream = http3Connection.sendConnect(connectRequest);

        // When
        byte[] buffer = new byte[100];
        ByteBuffer data = ByteBuffer.allocate(100);
        int c;
        while ((c = httpStream.getInputStream().read(buffer)) > 0) {
            data.put(buffer, 0, c);
        }

        assertThat(data.position()).isEqualTo(11);
        assertThat(new String(Arrays.copyOfRange(data.array(), 0, 11))).isEqualTo("hello world");
    }

    @Test
    public void httpStreamShouldCopyByteArrayRangeFromDataFrame() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        //                                       Header Frame  Data Frame       Data Frame  Data Frame
        byte[] inputData = ByteUtils.hexToBytes("01 00         00 05 68656c6c6f 00 01 20    00 05 776f726c64");
        mockQuicConnectionWithStreams(http3Connection, inputData);

        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        Http3ClientConnectionImpl.HttpStreamImpl httpStream = http3Connection.sendConnect(connectRequest);

        // When
        byte[] data = new byte[100];
        int read = 0;
        int total = 0;
        while ((read = httpStream.getInputStream().read(data, total, data.length)) > 0) {
            total += read;
        }

        assertThat(total).isEqualTo(11);
        assertThat(new String(Arrays.copyOfRange(data, 0, 11))).isEqualTo("hello world");
    }

    @Test
    public void httpStreamShouldReadSingleBytesFromDataFrame() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        //                                       Header Frame  Data Frame       Data Frame  Data Frame
        byte[] inputData = ByteUtils.hexToBytes("01 00         00 05 68656c6c6f 00 01 20    00 05 776f726c64");
        mockQuicConnectionWithStreams(http3Connection, inputData);

        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        Http3ClientConnectionImpl.HttpStreamImpl httpStream = http3Connection.sendConnect(connectRequest);

        // When
        ByteBuffer data = ByteBuffer.allocate(100);
        int b;
        while ((b = httpStream.getInputStream().read()) != -1) {
            data.put((byte) b);
        }

        assertThat(data.position()).isEqualTo(11);
        assertThat(new String(Arrays.copyOfRange(data.array(), 0, 11))).isEqualTo("hello world");
    }

    @Test
    public void sendExtendedConnectShouldFailWhenServerDoesNotSendSettingsFrame() throws Exception {
        // Given
        Http3ClientConnection http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();

        // Then
        assertThatThrownBy(() ->
                // When
                http3Connection.sendExtendedConnect(connectRequest, "websocket", "https", Duration.ofMillis(10))
        ).isInstanceOf(HttpError.class);
    }

    @Test
    public void sendExtendedConnectShouldFailWhenServerDoesNotSupportIt() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        // Do _not_ send SETTINGS_ENABLE_CONNECT_PROTOCOL setting on control stream
        http3Connection.registerServerInitiatedStream(createControlStream());
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();

        // Then
        assertThatThrownBy(() ->
                // When
                http3Connection.sendExtendedConnect(connectRequest, "websocket", "https", Duration.ofMillis(100))
        ).isInstanceOf(HttpError.class);
    }

    @Test
    public void sendExtendedConnectShouldSendProtocolPseudoHeader() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[] { 0x01, 0x00 });
        Encoder mockedQPackEncoder = mock(Encoder.class);
        when(mockedQPackEncoder.compressHeaders(anyList())).thenReturn(ByteBuffer.allocate(0));
        FieldSetter.setField(http3Connection, Http3ClientConnectionImpl.class.getDeclaredField("qpackEncoder"), mockedQPackEncoder);

        http3Connection.registerServerInitiatedStream(createControlStream(SETTINGS_ENABLE_CONNECT_PROTOCOL, 1));

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443"))
                .build();
        http3Connection.sendExtendedConnect(connectRequest, "websocket", "https", Duration.ofMillis(100));

        // Then
        ArgumentCaptor<List<Map.Entry<String, String>>> headersCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockedQPackEncoder).compressHeaders(headersCaptor.capture());
        Map<String, String> headers = headersCaptor.getValue().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(headers).containsAllEntriesOf(Map.of(
                ":method", "CONNECT",
                ":authority", "proxy.net:443",
                ":protocol", "websocket",
                ":scheme", "https"
        ));
    }

    @Test
    public void sendExtendedConnectShouldSendPathPseudoHeader() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[] { 0x01, 0x00});
        Encoder mockedQPackEncoder = mock(Encoder.class);
        when(mockedQPackEncoder.compressHeaders(anyList())).thenReturn(ByteBuffer.allocate(0));
        FieldSetter.setField(http3Connection, Http3ClientConnectionImpl.class.getDeclaredField("qpackEncoder"), mockedQPackEncoder);

        http3Connection.registerServerInitiatedStream(createControlStream(SETTINGS_ENABLE_CONNECT_PROTOCOL, 1));

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443/ws/echo?apiKey=de67fa"))
                .build();
        http3Connection.sendExtendedConnect(connectRequest, "websocket", "https", Duration.ofMillis(100));

        // Then
        ArgumentCaptor<List<Map.Entry<String, String>>> headersCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockedQPackEncoder).compressHeaders(headersCaptor.capture());
        Map<String, String> headers = headersCaptor.getValue().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(headers).containsKeys(":path");
        String pathValue = headers.get(":path");
        assertThat(pathValue).isEqualTo("/ws/echo?apiKey=de67fa");
    }

    @Test
    public void sendExtendedConnectShouldSendHeaders() throws Exception {
        // Given
        Http3ClientConnectionImpl http3Connection = new Http3ClientConnectionImpl("localhost", 4433);
        mockQuicConnectionWithStreams(http3Connection, new byte[] { 0x01, 0x00});
        // Must use mocked encoder to be able to capture headers in validate step.
        Encoder mockedQPackEncoder = mock(Encoder.class);
        when(mockedQPackEncoder.compressHeaders(anyList())).thenReturn(ByteBuffer.allocate(0));
        FieldSetter.setField(http3Connection, Http3ClientConnectionImpl.class.getDeclaredField("qpackEncoder"), mockedQPackEncoder);

        // Server must send SETTINGS_ENABLE_CONNECT_PROTOCOL setting on control stream in order to let client use extended CONNECT.
        http3Connection.registerServerInitiatedStream(createControlStream(SETTINGS_ENABLE_CONNECT_PROTOCOL, 1));

        // When
        HttpRequest connectRequest = HttpRequest.newBuilder()
                .uri(new URI("http://proxy.net:443/ws/echo?apiKey=de67fa"))
                .header("x-test", "testValue")
                .build();
        http3Connection.sendExtendedConnect(connectRequest, "websocket", "https", Duration.ofMillis(100));

        // Then
        ArgumentCaptor<List<Map.Entry<String, String>>> headersCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockedQPackEncoder).compressHeaders(headersCaptor.capture());
        Map<String, String> headersSent = headersCaptor.getValue().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(headersSent).containsKeys("x-test");
        assertThat(headersSent.get("x-test")).isEqualTo("testValue");
    }

    private QuicStream createControlStream(Integer... additionalBytes) {
        QuicStream quicStream = mock(QuicStream.class);
        //                                  stream type stream frame type
        byte[] data = ByteUtils.hexToBytes("00          04"
                // length (settings frame with 2 vars (01, 07) and additional data)
                + String.format("%02x", 4 + additionalBytes.length)
                // settings frame with at least 2 vars (01, 07) and whatever additional data is supplied
                + "01 00 07 0a");
        data = Arrays.copyOf(data, data.length + additionalBytes.length);
        for (int i = 0; i < additionalBytes.length; i++) {
            data[data.length - additionalBytes.length + i] = additionalBytes[i].byteValue();
        }
        when(quicStream.getInputStream()).thenReturn(new ByteArrayInputStream(data));
        return quicStream;
    }

    /**
     * Inserts a mock QuicConnection into the given Http3Connection object.
     * The mocked QuicConnection will return the given response bytes as output on the first bidirectional QuicStream
     * that is created on it (which is usually the request-response stream). The QPack decoder that is used to read
     * response headers will be mocked to return the given header frames contents. This reliefs the caller from duty to
     * assemble a complete and correct (QPack) header payload. As a consequence, the bytes specificied in the response
     * parameter can contain an empty header, provided the header frames contents contain the mandatory headers.
     *
     * @param http3Connection
     * @param response
     * @param headerFramesContents
     * @throws NoSuchFieldException
     * @throws IOException
     * @return
     */
    private QuicStream mockQuicConnectionWithStreams(Http3ClientConnection http3Connection, byte[] response, Map<String, String>... headerFramesContents) throws NoSuchFieldException, IOException {
        QuicClientConnection quicConnection = mock(QuicClientConnection.class);
        FieldSetter.setField(http3Connection, Http3ConnectionImpl.class.getDeclaredField("quicConnection"), quicConnection);

        QuicStream http3StreamMock = mock(QuicStream.class);
        when(quicConnection.createStream(anyBoolean())).thenReturn(http3StreamMock);
        // Create sink to send the http3 request bytes to.
        when(http3StreamMock.getOutputStream()).thenReturn(mock(OutputStream.class));

        // Return given response on QuicStream's input stream
        when(http3StreamMock.getInputStream()).thenReturn(new ByteArrayInputStream(response));

        // To relief caller from duty to assemble a complete and correct (QPack) header payload, the qpackDecoder is
        // mocked to return decent headers.
        Decoder mockedQPackDecoder = mock(Decoder.class);
        FieldSetter.setField(http3Connection, Http3ConnectionImpl.class.getDeclaredField("qpackDecoder"), mockedQPackDecoder);
        when(mockedQPackDecoder.decodeStream(any(InputStream.class))).thenAnswer(new Answer() {
            private int invocation = 0;
            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                Map<String, String> headers;
                if (headerFramesContents.length == 0) {
                    if (invocation == 0) {
                        headers = Map.of(":status", "200");
                    }
                    else {
                        headers = emptyMap();
                    }
                }
                else {
                    headers = headerFramesContents[invocation];
                }
                invocation++;
                return headers.entrySet().stream().collect(Collectors.toList());
            }
        });

        return http3StreamMock;
    }

    private QuicClientConnection mockQuicConnection(Http3ClientConnection http3Connection) throws NoSuchFieldException, IOException {
        QuicClientConnection quicConnection = mock(QuicClientConnection.class);
        FieldSetter.setField(http3Connection, Http3ConnectionImpl.class.getDeclaredField("quicConnection"), quicConnection);

        QuicStream http3StreamMock = mock(QuicStream.class);
        when(quicConnection.createStream(anyBoolean())).thenReturn(http3StreamMock);
        // Create sink to send the http3 request bytes to.
        when(http3StreamMock.getOutputStream()).thenReturn(mock(OutputStream.class));

        return quicConnection;
    }

    private class NoOpEncoder extends Encoder {
        @Override
        public ByteBuffer compressHeaders(List<Map.Entry<String, String>> headers) {
            mockEncoderCompressedHeaders = headers;
            return mock(ByteBuffer.class);
        }
    }

    private class NoOpDecoder extends Decoder {
        @Override
        public List<Map.Entry<String, String>> decodeStream(InputStream inputStream) throws IOException {
            return mockEncoderCompressedHeaders;
        }
    }
}
