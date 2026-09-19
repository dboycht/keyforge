using System;
using System.Linq;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

// Read-only probe: does Windows' own cache for the paired phone contain the
// Bluetooth HID service (UUID 00001124)? That is the service our Android app
// registers, and its absence here explains why Windows drops the HID channel.
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

        // Selection: explicit address wins, then a name substring, then the
        // project's own phone name. Supplied by the wrapper script as environment
        // variables so the command line stays simple.
        var wantedName = Environment.GetEnvironmentVariable("BT_PROBE_NAME");
        var wantedAddress = Environment.GetEnvironmentVariable("BT_PROBE_ADDRESS");

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

        target ??= infos.FirstOrDefault(i => i.Name.Contains("K3", StringComparison.OrdinalIgnoreCase))
                  ?? infos.FirstOrDefault(i => i.Name.Contains("keyforge", StringComparison.OrdinalIgnoreCase));

        if (target is null)
        {
            Console.WriteLine("phone not found among paired devices (pass -Name or -Address)");
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
