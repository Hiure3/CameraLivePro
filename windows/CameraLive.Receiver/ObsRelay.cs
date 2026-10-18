using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using CameraLive.Protocol;

namespace CameraLive.Receiver;

/// <summary>
/// Servidor TCP local (somente 127.0.0.1) que repassa o H.264 Annex-B recebido do celular.
/// O OBS (Fonte de Mídia) ou outro player FFmpeg conecta em tcp://127.0.0.1:47810 com formato "h264".
/// Cada cliente só começa a receber a partir de um quadro-chave (SPS/PPS vêm junto, ver Streamer.kt).
/// </summary>
public sealed class ObsRelay : IDisposable
{
    sealed class Client(TcpClient tcp)
    {
        public readonly TcpClient Tcp = tcp;
        public readonly Channel<byte[]> Queue = Channel.CreateBounded<byte[]>(
            new BoundedChannelOptions(90) { FullMode = BoundedChannelFullMode.Wait });
        public volatile bool WaitingForKey = true;
    }

    readonly TcpListener _listener = new(IPAddress.Loopback, Ports.ObsRelay);
    readonly List<Client> _clients = new();
    readonly CancellationTokenSource _cts = new();
    public int ClientCount { get { lock (_clients) return _clients.Count; } }

    public void Start()
    {
        _listener.Start();
        _ = Task.Run(AcceptLoop);
        Log.Write($"Relé OBS ouvindo em tcp://127.0.0.1:{Ports.ObsRelay}");
    }

    async Task AcceptLoop()
    {
        try
        {
            while (!_cts.IsCancellationRequested)
            {
                var tcp = await _listener.AcceptTcpClientAsync(_cts.Token);
                tcp.NoDelay = true;
                var c = new Client(tcp);
                lock (_clients) _clients.Add(c);
                Log.Write("OBS/Player conectado ao relé de vídeo.");
                _ = Task.Run(() => Pump(c));
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { Log.Write("Relé: " + ex.Message); }
    }

    async Task Pump(Client c)
    {
        try
        {
            var s = c.Tcp.GetStream();
            await foreach (var chunk in c.Queue.Reader.ReadAllAsync(_cts.Token))
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
                timeout.CancelAfter(TimeSpan.FromSeconds(3));          // cliente lento é desconectado
                await s.WriteAsync(chunk, timeout.Token);
            }
        }
        catch { /* cliente saiu ou ficou lento */ }
        finally
        {
            lock (_clients) _clients.Remove(c);
            c.Tcp.Dispose();
            Log.Write("OBS/Player desconectado do relé de vídeo.");
        }
    }

    public void Publish(bool keyFrame, ReadOnlyMemory<byte> annexB)
    {
        Client[] snapshot;
        lock (_clients) snapshot = _clients.ToArray();
        if (snapshot.Length == 0) return;
        byte[]? data = null;
        foreach (var c in snapshot)
        {
            if (c.WaitingForKey && !keyFrame) continue;
            data ??= annexB.ToArray();
            if (c.Queue.Writer.TryWrite(data)) c.WaitingForKey = false;
            else c.WaitingForKey = true;                               // fila cheia: espera próximo quadro-chave
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _listener.Stop(); } catch { }
        lock (_clients) foreach (var c in _clients) c.Tcp.Dispose();
    }
}
