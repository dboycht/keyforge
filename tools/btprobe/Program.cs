using System;
using System.Linq;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

// Read-only probe: does Windows' own cache for a paired phone contain the
// Bluetooth HID service (UUID 00001124)? That is the service our Android app
// registers, and its absence here explains why Windows drops the HID channel.
//
// Caveat (measured): GetRfcommServicesAsync returns what Windows recorded for the
// device, so a HID record only shows up if the app was registered when the host
// last paired/refreshed. Absence is therefore evidence about *host knowledge*,
// not proof that the phone never publishes HID.
internal static class Program
{
    private static readonly Guid HidService = new("00001124-0000-1000-8000-00805f9b34fb");
    private static readonly Guid HidOverGatt = new("00001812-0000-1000-8000-00805f9b34fb");

    private static async Task<int> Main()
    {
        Console.WriteLine("== paired Bluetooth devices ==");
        var selector = BluetoothDevice.GetDeviceSelector();
        var infos = await DeviceInformation.FindAllAsync(selector);
        foreach (var info in infos)
        {
            Console.WriteLine($"  {info.Name}  id={info.Id}");
        }

        // Supplied by the wrapper script as environment variables.
        var wantedName = Environment.GetEnvironmentVariable("BT_PROBE_NAME");
        var wantedAddress = Environment.GetEnvironmentVariable("BT_PROBE_ADDRESS");

        // Selection: explicit address wins, then a name substring. When neither is
        // given, prefer a device that is NOT known to be an audio-only peripheral
        // (the phone renames itself, so never match on a hard-coded model name).
        DeviceInformation? target = null;
        if (!string.IsNullOrWhiteSpace(wantedAddress))
        {
            var needle = wantedAddress.Replace(":", string.Empty).Replace("-", string.Empty);
            target = infos.FirstOrDefault(i =>
                i.Id.Replace(":", string.Empty).Replace("-", string.Empty)
                    .Contains(needle, StringComparison.OrdinalIgnoreCase));
        }

        if (target is null && !string.IsNullOrWhiteSpace(wantedName))
        {
            target = infos.FirstOrDefault(i => i.Name.Contains(wantedName, StringComparison.OrdinalIgnoreCase));
        }

        if (target is null)
        {
            Console.WriteLine("(no -Name/-Address given: probing each paired device until one exposes HID)");
            foreach (var candidate in infos)
            {
                var probed = await BluetoothDevice.FromIdAsync(candidate.Id);
                if (probed is null) continue;
                var services = await probed.GetRfcommServicesAsync(BluetoothCacheMode.Uncached);
                if (services.Error != Windows.Devices.Bluetooth.BluetoothError.Success) continue;
                if (services.Services.Any(s => s.ServiceId.Uuid == HidService || s.ServiceId.Uuid == HidOverGatt))
                {
                    target = candidate;
                    break;
                }
            }
        }

        if (target is null)
        {
            Console.WriteLine("no paired device exposes a HID service (pass -Name or -Address to inspect one anyway)");
            return 2;
        }

        var device = await BluetoothDevice.FromIdAsync(target.Id);
        if (device is null)
        {
            Console.WriteLine($"FromIdAsync returned null for {target.Name}");
            return 3;
        }

        Console.WriteLine();
        Console.WriteLine($"== target: {device.Name} ({device.BluetoothAddress:X12}) ==");
        Console.WriteLine($"  ConnectionStatus : {device.ConnectionStatus}");
        Console.WriteLine($"  Class            : {device.ClassOfDevice?.RawValue}");

        Console.WriteLine();
        Console.WriteLine("== cached RFCOMM services (what Windows knows this device offers) ==");
        var result = await device.GetRfcommServicesAsync(BluetoothCacheMode.Uncached);
        if (result.Error != Windows.Devices.Bluetooth.BluetoothError.Success)
        {
            Console.WriteLine($"  GetRfcommServicesAsync error: {result.Error} (cached attempt follows)");
            result = await device.GetRfcommServicesAsync(BluetoothCacheMode.Cached);
        }
        Console.WriteLine($"  error={result.Error}  count={result.Services.Count}");
        foreach (var svc in result.Services)
        {
            var uuid = svc.ServiceId.Uuid;
            var isHid = uuid == HidService || uuid == HidOverGatt;
            Console.WriteLine($"  {(isHid ? "HID >>>" : "       ")} {uuid}  {svc.ConnectionServiceName}");
        }

        var hasHid = result.Services.Any(s => s.ServiceId.Uuid == HidService || s.ServiceId.Uuid == HidOverGatt);
        Console.WriteLine();
        Console.WriteLine(hasHid
            ? "RESULT: Windows DOES see a HID service on the phone."
            : "RESULT: Windows sees NO HID service on the phone (only the services listed above).");
        return hasHid ? 0 : 1;
    }
}
