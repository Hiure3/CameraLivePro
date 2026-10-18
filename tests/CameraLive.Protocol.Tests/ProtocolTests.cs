using System.Net;
using System.Net.Sockets;
using CameraLive.Protocol;
using Xunit;

namespace CameraLive.Protocol.Tests;

public class ProtocolTests
{
    // Vetores gerados por scripts/protocol_vectors.py (implementação de referência independente em Python).
    static readonly byte[] NonceC = Convert.FromHexString("000102030405060708090a0b0c0d0e0f");
    static readonly byte[] NonceS = Convert.FromHexString("101112131415161718191a1b1c1d1e1f");

    [Fact]
    public void Master_MatchesPythonVector() =>
        Assert.Equal("f1cd2fe4f6fb95a709e196b9ac2d19c072e65eb4091a7a397cc276b78894ebb7",
            Convert.ToHexString(Pairing.Master("12345678")).ToLowerInvariant());

    [Theory]
    [InlineData('C', "abc59ecdf4232eb29e093626648160aaa72adcbfbe6929f70b92d17191cd3f40")]
    [InlineData('S', "d6819d9e14d0941d8164da739fe4de34b9cc18e45744e0c9257a4f06922a4d13")]
    [InlineData('K', "206ea5305fdf3f85d5a0006939352ce70a39fa2c6521d49b91c66809a311f15f")]
    public void Mac_MatchesPythonVector(char tag, string expected) =>
        Assert.Equal(expected, Convert.ToHexString(Pairing.Mac(Pairing.Master("12345678"), tag, NonceC, NonceS)).ToLowerInvariant());

    [Fact]
    public void Seal_MatchesPythonVector()
    {
        var key = Convert.FromHexString("206ea5305fdf3f85d5a0006939352ce70a39fa2c6521d49b91c66809a311f15f");
        var sealedBytes = SecureChannel.Seal(key, 1, 0, MsgType.Video, System.Text.Encoding.ASCII.GetBytes("hello-video"));
        Assert.Equal("1f73cefd4f8e6786c7397980d2b1ce79a2899d485ffafb5679c01e", Convert.ToHexString(sealedBytes).ToLowerInvariant());
    }

    [Fact]
    public void NewCode_HasEightDigits() => Assert.Matches("^[0-9]{8}$", Pairing.NewCode());

    static async Task<(Stream server, Stream client)> LoopbackPair()
    {
        var l = new TcpListener(IPAddress.Loopback, 0); l.Start();
        var c = new TcpClient(); var acceptTask = l.AcceptTcpClientAsync();
        await c.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)l.LocalEndpoint).Port);
        var s = await acceptTask; l.Stop();
        return (s.GetStream(), c.GetStream());
    }

    [Fact]
    public async Task Handshake_AndEncryptedRoundTrip()
    {
        var (s, c) = await LoopbackPair();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var server = Handshake.ServerAsync(s, "00001234", cts.Token);
        var client = Handshake.ClientAsync(c, "00001234", "Pixel", cts.Token);
        var (srvCh, peer) = await server; var cliCh = await client;
        Assert.Equal("Pixel", peer.Name);

        var payload = new byte[] { 1, 2, 3, 4, 5 };
        await cliCh.WriteAsync(MsgType.Video, payload, cts.Token);
        var (t, got) = await srvCh.ReadAsync(cts.Token);
        Assert.Equal(MsgType.Video, t); Assert.Equal(payload, got);
        await srvCh.WriteAsync(MsgType.Control, payload, cts.Token);
        var (t2, got2) = await cliCh.ReadAsync(cts.Token);
        Assert.Equal(MsgType.Control, t2); Assert.Equal(payload, got2);
    }

    [Fact]
    public async Task Handshake_WrongCode_IsRejectedOnBothSides()
    {
        var (s, c) = await LoopbackPair();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var server = Handshake.ServerAsync(s, "11111111", cts.Token);
        var client = Handshake.ClientAsync(c, "22222222", "X", cts.Token);
        await Assert.ThrowsAsync<ProtocolException>(() => server);
        await Assert.ThrowsAsync<ProtocolException>(() => client);
    }

    [Fact]
    public async Task TamperedFrame_IsRejected()
    {
        var key = new byte[32];
        var ms = new MemoryStream();
        var tx = new SecureChannel(ms, key, 1, 2);
        await tx.WriteAsync(MsgType.Video, new byte[] { 9, 9, 9 }, CancellationToken.None);
        var bytes = ms.ToArray(); bytes[6] ^= 0xFF;                     // adultera o texto cifrado
        var rx = new SecureChannel(new MemoryStream(bytes), key, 2, 1);
        await Assert.ThrowsAsync<ProtocolException>(() => rx.ReadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task ReplayedFrame_IsRejected()
    {
        var key = new byte[32];
        var ms = new MemoryStream();
        var tx = new SecureChannel(ms, key, 1, 2);
        await tx.WriteAsync(MsgType.Ping, new byte[8], CancellationToken.None);
        var frame = ms.ToArray();
        var rx = new SecureChannel(new MemoryStream(frame.Concat(frame).ToArray()), key, 2, 1);
        await rx.ReadAsync(CancellationToken.None);                      // 1ª vez: ok
        await Assert.ThrowsAsync<ProtocolException>(() => rx.ReadAsync(CancellationToken.None)); // repetido
    }

    [Fact]
    public async Task OversizedFrame_IsRejected()
    {
        var bytes = new byte[] { MsgType.Video, 0xFF, 0xFF, 0xFF, 0xFF };
        await Assert.ThrowsAsync<ProtocolException>(() => FrameIO.ReadAsync(new MemoryStream(bytes), CancellationToken.None));
    }

    [Fact]
    public void VideoPacket_Parses()
    {
        var p = new byte[] { 0,0,0,0,0,0,0,5, 1, 0xAA, 0xBB };
        var v = VideoPacket.Parse(p);
        Assert.Equal(5, v.PtsUs); Assert.True(v.KeyFrame); Assert.Equal(2, v.Data.Length);
    }
}
