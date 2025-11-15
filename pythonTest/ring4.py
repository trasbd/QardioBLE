import asyncio
from bleak import BleakClient
import datetime
from jlparser import *

RING_MAC = "30:33:00:00:31:3F"
UUID_WRITE   = "0000b002-0000-1000-8000-00805f9b34fb"
UUID_NOTIFY  = "0000b003-0000-1000-8000-00805f9b34fb"

def build_cmd(cmd_id, sub, action, end):
    return bytes([0xAB, 0x01, 0x00, 0x03, cmd_id, sub, 0x05, action, end])

# handshake commands
CMD_AUTH_START    = build_cmd(0xAD, 0x1A, 0x1A, 0x10)
CMD_INFO_REQUEST  = build_cmd(0x75, 0x1B, 0x1A, 0x30)
CMD_FUNCTION_LIST = build_cmd(0xAD, 0x10, 0x02, 0x10)
CMD_ENABLE_HR     = build_cmd(0x9D, 0x12, 0x05, 0x10)
CMD_START_STREAM  = build_cmd(0x3D, 0x11, 0x03, 0x10)

# ------------------------------------------------------------------

def handle_notify(sender, data: bytes):
    # We expect: ab 11 00 ?? ?? ?? 05 03 10 <payload>
    if len(data) < 15:
        print(f"[Notify] {data.hex(' ')}")
        return

    # Verify JL health tag
    if not (data.startswith(b"\xAB\x11\x00") and data[6:9] == b"\x05\x03\x10"):
        print(f"[Notify] {data.hex(' ')}")
        return

    print(f"[HR-JL] {data.hex(' ')}")

    # 1) JL CMD-ID for HR history (detected from APK)
    jl_cmd_id = b"\x02\x24\x00"   # THIS IS FROM THE RWFit MAP, AND CORRECT

    # 2) Extract the JL payload starting at BLE offset 9
    jl_payload = data[9:]

    # 3) Build JL frame
    jl_frame = jl_cmd_id + jl_payload

    # 4) Run the REAL JL parser
    entries = parse_jl_hr_history_frame(jl_frame)

    # 5) Print results
    if not entries:
        print("  (no HR entries)")
        return

    for dt, bpm in entries:
        print(f"  {dt:%Y-%m-%d %H:%M:%S} → {bpm} bpm")

# ------------------------------------------------------------------

async def main():
    cmds = [
        ("Auth start",    CMD_AUTH_START),
        ("Info request",  CMD_INFO_REQUEST),
        ("Function list", CMD_FUNCTION_LIST),
        ("Enable HR",     CMD_ENABLE_HR),
        ("Start stream",  CMD_START_STREAM),
    ]

    async with BleakClient(RING_MAC, timeout=30.0) as client:
        print("✅ Connected")
        await client.start_notify(UUID_NOTIFY, handle_notify)

        for label, cmd in cmds:
            print(f"➡️ {label} {cmd.hex()}")
            await client.write_gatt_char(UUID_WRITE, cmd)
            await asyncio.sleep(.1)

        print("✅ Waiting for HR history notifications...")
        await asyncio.sleep(15)

        await client.stop_notify(UUID_NOTIFY)
        print("✅ Done")

if __name__ == "__main__":
    asyncio.run(main())
