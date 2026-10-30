using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace WizMusicSync.Core;

/// <summary>WiZ local UDP API (port 38899): command payloads, one-off sends, power and bulb discovery.</summary>
public static class WizBulb
{
    public const int Port = 38899;

    public sealed record Found(string Ip, string Mac, string? Model);

    public static string Payload(BulbLight light)
    {
        var parameters = new JsonObject();
        switch (light)
        {
            case RgbLight rgb:
                parameters["r"] = rgb.R;
                parameters["g"] = rgb.G;
                parameters["b"] = rgb.B;
                parameters["c"] = 0;
                parameters["w"] = 0;
                break;
            case WhiteLight white:
                // Clean natural white using the bulb's dedicated white diodes
                parameters["temp"] = white.Temp;
                break;
        }
        parameters["state"] = true;
        parameters["dimming"] = Math.Clamp(light.Dimming, 10, 100);
        parameters["speed"] = 100;
        return new JsonObject { ["id"] = 1, ["method"] = "setPilot", ["params"] = parameters }.ToJsonString();
    }

    /// <summary>Fire-and-forget send. Blocking network call.</summary>
    public static void SendOnce(string ip, string payload)
    {
        try
        {
            using var udp = new UdpClient();
            var bytes = Encoding.UTF8.GetBytes(payload);
            udp.Send(bytes, bytes.Length, ip, Port);
        }
        catch (Exception)
        {
        }
    }

    /// <summary>Asks the bulb whether it is on; null if it doesn't answer within <paramref name="timeoutMs"/>. Blocking.</summary>
    public static bool? IsOn(string ip, int timeoutMs = 700)
    {
        var result = Request(ip, """{"method":"getPilot","params":{}}""", timeoutMs);
        return result?["state"]?.GetValue<bool>();
    }

    /// <summary>
    /// Switches the bulb on (back to whatever it last showed) or off, retrying until the bulb confirms or
    /// <paramref name="timeoutMs"/> runs out — Wi-Fi can take a few seconds after the PC wakes. Blocking.
    /// </summary>
    /// <returns>True once the bulb reports the requested state.</returns>
    public static bool SetPower(string ip, bool on, int timeoutMs = 8000)
    {
        string payload = new JsonObject
        {
            ["id"] = 1,
            ["method"] = "setState",
            ["params"] = new JsonObject { ["state"] = on },
        }.ToJsonString();
        var end = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < end)
        {
            SendOnce(ip, payload);
            if (IsOn(ip) == on) return true;
            Thread.Sleep(300);
        }
        return false;
    }

    /// <summary>
    /// Broadcasts WiZ discovery messages on every local network and collects the bulbs that answer.
    /// Blocking for about <paramref name="listenMs"/>.
    /// </summary>
    public static List<Found> Discover(int listenMs = 3000)
    {
        var found = new Dictionary<string, string>(); // ip -> mac
        byte[][] probes =
        {
            Encoding.UTF8.GetBytes("""{"method":"registration","params":{"phoneMac":"AAAAAAAAAAAA","register":false,"phoneIp":"1.2.3.4","id":"1"}}"""),
            Encoding.UTF8.GetBytes("""{"method":"getPilot","params":{}}"""),
        };
        var targets = BroadcastAddresses().Append(IPAddress.Broadcast).Distinct().ToList();

        using (var udp = new UdpClient())
        {
            udp.EnableBroadcast = true;
            udp.Client.ReceiveTimeout = 200;
            var end = DateTime.UtcNow.AddMilliseconds(listenMs);
            var nextProbe = DateTime.MinValue;
            int probesSent = 0;
            while (DateTime.UtcNow < end)
            {
                // Repeat the probe a few times: UDP broadcasts are easily dropped on busy Wi-Fi
                if (probesSent < 3 && DateTime.UtcNow >= nextProbe)
                {
                    foreach (var target in targets)
                    foreach (var probe in probes)
                    {
                        try { udp.Send(probe, probe.Length, new IPEndPoint(target, Port)); }
                        catch (Exception) { }
                    }
                    probesSent++;
                    nextProbe = DateTime.UtcNow.AddMilliseconds(800);
                }
                try
                {
                    var from = new IPEndPoint(IPAddress.Any, 0);
                    var data = udp.Receive(ref from);
                    var mac = JsonNode.Parse(data)?["result"]?["mac"]?.GetValue<string>();
                    if (!string.IsNullOrEmpty(mac)) found[from.Address.ToString()] = mac;
                }
                catch (Exception)
                {
                    // timeout or a non-WiZ reply: keep listening
                }
            }
        }

        return found.Select(kv => new Found(kv.Key, FormatMac(kv.Value), ModelOf(kv.Key))).ToList();
    }

    /// <summary>Asks one bulb for its module name (e.g. ESP25_SHRGB_01); null if it doesn't answer.</summary>
    private static string? ModelOf(string ip)
    {
        var name = Request(ip, """{"method":"getSystemConfig","params":{}}""", 600)?["moduleName"]?.GetValue<string>();
        return string.IsNullOrEmpty(name) ? null : name;
    }

    /// <summary>Sends one request to a bulb and returns its "result" object, or null.</summary>
    private static JsonNode? Request(string ip, string request, int timeoutMs)
    {
        try
        {
            using var udp = new UdpClient();
            udp.Client.ReceiveTimeout = timeoutMs;
            var bytes = Encoding.UTF8.GetBytes(request);
            udp.Send(bytes, bytes.Length, ip, Port);
            var from = new IPEndPoint(IPAddress.Any, 0);
            return JsonNode.Parse(udp.Receive(ref from))?["result"];
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>Directed broadcast address of each active IPv4 network (e.g. 192.168.0.255).</summary>
    private static IEnumerable<IPAddress> BroadcastAddresses()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                continue;
            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork || unicast.PrefixLength is <= 0 or > 30)
                    continue;
                uint ip = ToUInt(unicast.Address);
                uint mask = uint.MaxValue << (32 - unicast.PrefixLength);
                uint broadcast = ip | ~mask;
                yield return new IPAddress(BitConverter.GetBytes(broadcast).Reverse().ToArray());
            }
        }
    }

    private static uint ToUInt(IPAddress address)
    {
        var b = address.GetAddressBytes();
        return (uint)(b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]);
    }

    private static string FormatMac(string mac) =>
        mac.Length == 12 ? string.Join(":", Enumerable.Range(0, 6).Select(i => mac.Substring(i * 2, 2))).ToUpperInvariant() : mac;
}
