using System.IO;
namespace CameraLive.Receiver;

/// <summary>Log simples em arquivo (%LOCALAPPDATA%\CameraLivePro\logs). Nunca grava o código de pareamento.</summary>
public static class Log
{
    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CameraLivePro", "logs");
    static readonly object Gate = new();
    public static event Action<string>? Line;

    public static void Write(string msg)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {msg}";
        try { lock (Gate) { Directory.CreateDirectory(Dir); File.AppendAllText(Path.Combine(Dir, "receiver.log"), line + Environment.NewLine); } }
        catch { /* log nunca deve derrubar o app */ }
        Line?.Invoke(line);
    }
}
