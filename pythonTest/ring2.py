import asyncio
from datetime import datetime
from bleak import BleakClient, BleakScanner

# === Ring Info ===
RING_MAC = "30:33:00:00:31:3F"
UUID_WRITE = "0000b002-0000-1000-8000-00805f9b34fb"
UUID_NOTIFY = "0000b003-0000-1000-8000-00805f9b34fb"

# === Build timestamp command ===
def build_timestamp_command() -> bytes:
    """
    ab 01 00 09 51 bf 02 01 00 YY MM DD HH MM SS
    """
    now = datetime.now()
    return bytes([
        0xAB, 0x01,
        0x00, 0x09,
        0x51, 0xBF,
        0x02, 0x01, 0x00,
        now.year % 100,
        now.month,
        now.day,
        now.hour,
        now.minute,
        now.second
    ])

# === Global state ===
_client = None
current_command_name = None
log_files = {}

# === Notification handler ===
def handle_notify(sender, data: bytearray):
    global current_command_name
    hex_str = data.hex(" ")
    print(f"[Notification] {hex_str}")

    # Save to a file named after the command
    if current_command_name:
        fname = f"ring_{current_command_name.replace(' ', '_').lower()}.log"
        with open(fname, "a") as f:
            f.write(f"[{datetime.now().isoformat()}] {hex_str}\n")
        log_files[current_command_name] = fname


# === Main ===
async def main():
    global _client, current_command_name

    print("🔍 Scanning for ring...")
    device = await BleakScanner.find_device_by_address(RING_MAC, timeout=15.0)
    if not device:
        print("❌ Ring not found.")
        return

    async with BleakClient(device) as client:
        _client = client
        print(f"✅ Connected to {device.address}")

        await client.start_notify(UUID_NOTIFY, handle_notify)
        print("📡 Notifications enabled.\n")

        # === Command sequence ===
        commands = [
            ("Session start",  bytes.fromhex("ab010003b8f0030220")),
            ("Device info",    bytes.fromhex("ab010003cca2020410")),
            ("Time sync",      build_timestamp_command()),
            ("Enable data",    bytes.fromhex("ab010004bba1020e0001")),
            ("Daily summary",  bytes.fromhex("ab010003fc88026310")),
            ("Steps",          bytes.fromhex("ab010003ac8b026610")),
            ("Sleep",          bytes.fromhex("ab010003cc8a026410")),
            ("Heart rate",     bytes.fromhex("ab010003cc8f026810")),
        ]

        for name, cmd in commands:
            current_command_name = name
            print(f"\n=== {name} ===")
            print(f"📤 Sending: {cmd.hex(' ')}")
            try:
                await client.write_gatt_char(UUID_WRITE, cmd, response=True)
                print("✅ Write OK")
            except Exception as e:
                print(f"⚠️ Write failed: {e}")

            # Give ring time to respond
            await asyncio.sleep(2.0)

        print("\n⏳ Waiting for more notifications (Ctrl+C to stop)...")
        try:
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            pass

        await client.stop_notify(UUID_NOTIFY)
        print("\n🔌 Disconnected")

        # Summary of saved logs
        if log_files:
            print("\n📁 Logs saved:")
            for name, fname in log_files.items():
                print(f" - {name}: {fname}")

if __name__ == "__main__":
    asyncio.run(main())
