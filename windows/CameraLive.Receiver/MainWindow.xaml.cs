using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CameraLive.Protocol;

namespace CameraLive.Receiver;

public partial class MainWindow : Window
{
    readonly ObsRelay _relay = new();
    readonly ReceiverServer _server;
    readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    DateTime _lastPreview = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();
        _server = new ReceiverServer(_relay);
        Log.Line += l => Dispatcher.BeginInvoke(() =>
        {
            LogList.Items.Add(l);
            if (LogList.Items.Count > 300) LogList.Items.RemoveAt(0);
            LogList.ScrollIntoView(LogList.Items[^1]);
        });
        _server.StatusChanged += s => Dispatcher.BeginInvoke(() => StatusText.Text = s);
        _server.PreviewReceived += OnPreview;
        _server.SessionUpdated += OnSession;
        try
        {
            _relay.Start();
            _server.Start();
        }
        catch (SocketException ex)
        {
            StatusText.Text = "Erro: porta em uso. Feche outra cópia do receptor.";
            StatusText.Foreground = System.Windows.Media.Brushes.Tomato;
            Log.Write("Falha ao abrir portas: " + ex.Message);
        }
        CodeText.Text = Format(_server.Code);
        AddressText.Text = string.Join("\n", LocalIPv4().Select(a => $"{a}:{Ports.Stream}"));
        _timer.Tick += (_, _) => RelayText.Text = "OBS conectado: " + (_relay.ClientCount > 0 ? "sim" : "não");
        _timer.Start();
        Closed += (_, _) => { _server.Dispose(); _relay.Dispose(); };
    }

    static string Format(string code) => code.Length == 8 ? $"{code[..4]} {code[4..]}" : code;

    static IEnumerable<IPAddress> LocalIPv4() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Select(a => a.Address).Where(a => a.AddressFamily == AddressFamily.InterNetwork);

    void OnPreview(byte[] jpeg)
    {
        if ((DateTime.UtcNow - _lastPreview).TotalMilliseconds < 200) return;
        _lastPreview = DateTime.UtcNow;
        try
        {
            var bmp = new BitmapImage();
            using (var ms = new MemoryStream(jpeg))
            {
                bmp.BeginInit(); bmp.CacheOption = BitmapCacheOption.OnLoad; bmp.StreamSource = ms; bmp.EndInit();
            }
            bmp.Freeze();
            Dispatcher.BeginInvoke(() => { PreviewImage.Source = bmp; NoVideoText.Visibility = Visibility.Collapsed; });
        }
        catch { /* quadro de prévia inválido: ignora */ }
    }

    void OnSession(SessionSnapshot? s) => Dispatcher.BeginInvoke(() =>
    {
        if (s is null) { StatsText.Text = "—"; PreviewImage.Source = null; NoVideoText.Visibility = Visibility.Visible; return; }
        var sb = new StringBuilder();
        if (s.Config is { } c) sb.AppendLine($"{c.Width}x{c.Height} @ {c.Fps} fps ({c.Camera})");
        sb.AppendLine($"Recebido: {s.RecvFps:F1} fps, {s.RecvKbps:F0} kbps");
        if (s.Stats is { } p) sb.AppendLine($"Latência (RTT): {p.RttMs} ms\nCelular: {p.Battery}% bateria, {p.TempC:F1} °C\nQuadros descartados: {p.Dropped}");
        StatsText.Text = sb.ToString().TrimEnd();
    });

    void NewCode_Click(object s, RoutedEventArgs e) { _server.NewCode(); CodeText.Text = Format(_server.Code); }
    void CopyUrl_Click(object s, RoutedEventArgs e) => Clipboard.SetText(ObsUrl.Text);
    void OpenLogs_Click(object s, RoutedEventArgs e) { Directory.CreateDirectory(Log.Dir); Process.Start("explorer.exe", Log.Dir); }

    void Help_Click(object s, RoutedEventArgs e) => MessageBox.Show(this,
        "OBS Studio:\n1. Em Fontes, clique em + e escolha 'Fonte de Mídia'.\n2. Desmarque 'Arquivo local'.\n" +
        "3. Em Entrada, digite: tcp://127.0.0.1:47810\n4. Em 'Formato de entrada', digite: h264\n" +
        "5. Desmarque 'Usar buffer de rede' para menor latência e clique OK.\n\n" +
        "Importante: inicie a transmissão no celular ANTES de adicionar a fonte (ou reinicie a fonte depois).\n\n" +
        "vMix: este caminho ainda NÃO foi testado no vMix. Veja docs/INTEGRATIONS.md.",
        "Como usar no OBS", MessageBoxButton.OK, MessageBoxImage.Information);

    void Diag_Click(object s, RoutedEventArgs e)
    {
        var sb = new StringBuilder();
        var ips = LocalIPv4().ToList();
        sb.AppendLine(ips.Count == 0 ? "✗ Nenhuma rede ativa encontrada. Conecte o PC ao Wi-Fi/cabo." : "✓ Rede ativa: " + string.Join(", ", ips));
        sb.AppendLine($"✓ Porta TCP {Ports.Stream} (celular → PC) e UDP {Ports.Discovery} (descoberta) em uso por este programa.");
        sb.AppendLine("• Se o celular não encontra o PC: use o IP acima manualmente.");
        sb.AppendLine("• Se nem pelo IP conecta: o Firewall do Windows pode estar bloqueando (reinstale marcando a opção de firewall) ou o roteador tem 'isolamento de clientes/AP isolation' ativo.");
        sb.AppendLine("• PC e celular precisam estar na mesma rede (não use rede de convidados).");
        MessageBox.Show(this, sb.ToString(), "Diagnóstico de rede", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
