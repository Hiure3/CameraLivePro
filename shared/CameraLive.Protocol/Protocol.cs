using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CameraLive.Protocol;

/// <summary>Tipos de mensagem do protocolo CLP v1 (ver docs/PROTOCOL.md).</summary>
public static class MsgType
{
    public const byte Hello = 1, Challenge = 2, Auth = 3, AuthOk = 4, AuthFail = 5;
    public const byte Config = 10, Video = 11, Preview = 12, Stats = 13, Ping = 14, Pong = 15, Control = 20;
}

public static class Ports
{
    public const int Stream = 47800;      // TCP: celular -> receptor
    public const int Discovery = 47801;   // UDP: descoberta por broadcast
    public const int ObsRelay = 47810;    // TCP (somente 127.0.0.1): H.264 Annex-B para OBS/vMix
}

public sealed class ProtocolException(string message) : Exception(message);

public static class Pairing
{
    static readonly byte[] Salt = Encoding.ASCII.GetBytes("CameraLivePro-v1");

    /// <summary>Gera código de pareamento de 8 dígitos com RNG criptográfico.</summary>
    public static string NewCode() => RandomNumberGenerator.GetInt32(0, 100_000_000).ToString("D8");

    public static byte[] Master(string code) =>
        Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(code), Salt, 100_000, HashAlgorithmName.SHA256, 32);

    /// <summary>HMAC-SHA256(key, tag || nonceC || nonceS). Tags: 'C' cliente, 'S' servidor, 'K' chave de sessão.</summary>
    public static byte[] Mac(byte[] key, char tag, byte[] nonceC, byte[] nonceS)
    {
        var data = new byte[1 + nonceC.Length + nonceS.Length];
        data[0] = (byte)tag;
        nonceC.CopyTo(data, 1);
        nonceS.CopyTo(data, 1 + nonceC.Length);
        return HMACSHA256.HashData(key, data);
    }
}

/// <summary>Quadros: [tipo:1][tamanho:4 big-endian][payload].</summary>
public static class FrameIO
{
    public const int MaxPayload = 16 * 1024 * 1024;

    public static async Task WriteAsync(Stream s, byte type, ReadOnlyMemory<byte> payload, CancellationToken ct)
    {
        var header = new byte[5];
        header[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(header.AsSpan(1), (uint)payload.Length);
        await s.WriteAsync(header, ct).ConfigureAwait(false);
        await s.WriteAsync(payload, ct).ConfigureAwait(false);
        await s.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async Task<(byte Type, byte[] Payload)> ReadAsync(Stream s, CancellationToken ct, int maxPayload = MaxPayload)
    {
        var header = new byte[5];
        await s.ReadExactlyAsync(header, ct).ConfigureAwait(false);
        uint len = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1));
        if (len > (uint)maxPayload) throw new ProtocolException("Quadro maior que o limite permitido.");
        var payload = new byte[len];
        await s.ReadExactlyAsync(payload, ct).ConfigureAwait(false);
        return (header[0], payload);
    }
}

/// <summary>Canal autenticado: AES-256-GCM, nonce = prefixo(4) || contador(8), AAD = cabeçalho do quadro.</summary>
public sealed class SecureChannel
{
    readonly Stream _s;
    readonly byte[] _key;
    readonly uint _sendPrefix, _recvPrefix;
    ulong _sendCounter, _recvCounter;
    readonly SemaphoreSlim _writeLock = new(1, 1);

    public SecureChannel(Stream s, byte[] sessionKey, uint sendPrefix, uint recvPrefix)
    { _s = s; _key = sessionKey; _sendPrefix = sendPrefix; _recvPrefix = recvPrefix; }

    static byte[] Nonce(uint prefix, ulong counter)
    {
        var n = new byte[12];
        BinaryPrimitives.WriteUInt32BigEndian(n, prefix);
        BinaryPrimitives.WriteUInt64BigEndian(n.AsSpan(4), counter);
        return n;
    }

    static byte[] Aad(byte type, int ctLen)
    {
        var a = new byte[5];
        a[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(a.AsSpan(1), (uint)ctLen);
        return a;
    }

    /// <summary>Cifra um payload (saída = texto cifrado || tag de 16 bytes). Exposto para testes.</summary>
    public static byte[] Seal(byte[] key, uint prefix, ulong counter, byte type, byte[] plaintext)
    {
        var output = new byte[plaintext.Length + 16];
        using var gcm = new AesGcm(key, 16);
        gcm.Encrypt(Nonce(prefix, counter), plaintext,
            output.AsSpan(0, plaintext.Length), output.AsSpan(plaintext.Length, 16), Aad(type, output.Length));
        return output;
    }

    public async Task WriteAsync(byte type, byte[] plaintext, CancellationToken ct)
    {
        await _writeLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var sealedBytes = Seal(_key, _sendPrefix, _sendCounter++, type, plaintext);
            await FrameIO.WriteAsync(_s, type, sealedBytes, ct).ConfigureAwait(false);
        }
        finally { _writeLock.Release(); }
    }

    public async Task<(byte Type, byte[] Payload)> ReadAsync(CancellationToken ct)
    {
        var (type, ct_) = await FrameIO.ReadAsync(_s, ct).ConfigureAwait(false);
        if (ct_.Length < 16) throw new ProtocolException("Quadro cifrado inválido.");
        var plain = new byte[ct_.Length - 16];
        try
        {
            using var gcm = new AesGcm(_key, 16);
            gcm.Decrypt(Nonce(_recvPrefix, _recvCounter), ct_.AsSpan(0, plain.Length),
                ct_.AsSpan(plain.Length, 16), plain, Aad(type, ct_.Length));
        }
        catch (CryptographicException)
        {
            throw new ProtocolException("Falha de autenticação do quadro (adulterado, repetido ou chave errada).");
        }
        _recvCounter++;
        return (type, plain);
    }
}

public sealed record PeerInfo(string Name);

public static class Handshake
{
    const uint ClientToServer = 1, ServerToClient = 2;
    static readonly JsonSerializerOptions J = new() { PropertyNameCaseInsensitive = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    sealed record HelloMsg(int V, string Name, string NonceC);
    sealed record ChallengeMsg(string NonceS);

    /// <summary>Lado do receptor (Windows). Lança ProtocolException se o código estiver errado.</summary>
    public static async Task<(SecureChannel Channel, PeerInfo Peer)> ServerAsync(Stream s, string code, CancellationToken ct)
    {
        var (t, p) = await FrameIO.ReadAsync(s, ct, 4096).ConfigureAwait(false);
        if (t != MsgType.Hello) throw new ProtocolException("Esperado HELLO.");
        var hello = JsonSerializer.Deserialize<HelloMsg>(p, J) ?? throw new ProtocolException("HELLO inválido.");
        if (hello.V != 1) throw new ProtocolException("Versão de protocolo não suportada.");
        var nonceC = Convert.FromBase64String(hello.NonceC);
        if (nonceC.Length != 16) throw new ProtocolException("Nonce inválido.");
        var name = new string((hello.Name ?? "Celular").Where(c => !char.IsControl(c)).Take(48).ToArray());

        var nonceS = RandomNumberGenerator.GetBytes(16);
        await FrameIO.WriteAsync(s, MsgType.Challenge,
            JsonSerializer.SerializeToUtf8Bytes(new ChallengeMsg(Convert.ToBase64String(nonceS)), J), ct).ConfigureAwait(false);

        var (t2, proof) = await FrameIO.ReadAsync(s, ct, 64).ConfigureAwait(false);
        var master = Pairing.Master(code);
        if (t2 != MsgType.Auth || !CryptographicOperations.FixedTimeEquals(proof, Pairing.Mac(master, 'C', nonceC, nonceS)))
        {
            await FrameIO.WriteAsync(s, MsgType.AuthFail, Array.Empty<byte>(), ct).ConfigureAwait(false);
            throw new ProtocolException("Código de pareamento incorreto.");
        }
        await FrameIO.WriteAsync(s, MsgType.AuthOk, Pairing.Mac(master, 'S', nonceC, nonceS), ct).ConfigureAwait(false);
        var key = Pairing.Mac(master, 'K', nonceC, nonceS);
        return (new SecureChannel(s, key, ServerToClient, ClientToServer), new PeerInfo(name));
    }

    /// <summary>Lado do cliente em C# (usado nos testes; o app Android implementa o mesmo em Kotlin).</summary>
    public static async Task<SecureChannel> ClientAsync(Stream s, string code, string name, CancellationToken ct)
    {
        var nonceC = RandomNumberGenerator.GetBytes(16);
        await FrameIO.WriteAsync(s, MsgType.Hello,
            JsonSerializer.SerializeToUtf8Bytes(new HelloMsg(1, name, Convert.ToBase64String(nonceC)), J), ct).ConfigureAwait(false);
        var (t, p) = await FrameIO.ReadAsync(s, ct, 4096).ConfigureAwait(false);
        if (t != MsgType.Challenge) throw new ProtocolException("Esperado CHALLENGE.");
        var nonceS = Convert.FromBase64String((JsonSerializer.Deserialize<ChallengeMsg>(p, J) ?? throw new ProtocolException("Inválido")).NonceS);
        var master = Pairing.Master(code);
        await FrameIO.WriteAsync(s, MsgType.Auth, Pairing.Mac(master, 'C', nonceC, nonceS), ct).ConfigureAwait(false);
        var (t2, sp) = await FrameIO.ReadAsync(s, ct, 64).ConfigureAwait(false);
        if (t2 != MsgType.AuthOk || !CryptographicOperations.FixedTimeEquals(sp, Pairing.Mac(master, 'S', nonceC, nonceS)))
            throw new ProtocolException("Pareamento recusado.");
        return new SecureChannel(s, Pairing.Mac(master, 'K', nonceC, nonceS), ClientToServer, ServerToClient);
    }
}

/// <summary>Payload de VIDEO: [pts µs:8 BE][flags:1 (bit0 = keyframe)][H.264 Annex-B].</summary>
public readonly record struct VideoPacket(long PtsUs, bool KeyFrame, ReadOnlyMemory<byte> Data)
{
    public static VideoPacket Parse(byte[] payload)
    {
        if (payload.Length < 10) throw new ProtocolException("Pacote de vídeo curto demais.");
        return new VideoPacket(BinaryPrimitives.ReadInt64BigEndian(payload), (payload[8] & 1) != 0, payload.AsMemory(9));
    }
}

public sealed record StreamConfig(int Width, int Height, int Fps, int BitrateKbps, string Codec, string Camera);
