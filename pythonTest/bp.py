import asyncio
from bleak import BleakClient
from secrets import BPCUFF_MAC

ADDR = BPCUFF_MAC
CTRL = "583cb5b3-875d-40ed-9098-c39eb0c1983d"  # confirmed control UUID
MEAS = "00002a35-0000-1000-8000-00805f9b34fb"  # blood pressure measurement
BAT  = "00002a19-0000-1000-8000-00805f9b34fb"  # battery (optional)

async def on_notify(sender, data: bytearray):
    print(f"[NOTIFY] {sender}: {data.hex('-')}")

async def main():
    async with BleakClient(ADDR) as client:
        if not client.is_connected:
            print("❌ could not connect")
            return
        print("✅ Connected")

        # Enable indications
        for uuid in [MEAS, BAT]:
            try:
                await client.start_notify(uuid, on_notify)
                print(f"Notify enabled: {uuid}")
            except Exception as e:
                print(f"Notify failed {uuid}: {e}")

        # ---- Start measurement ----
        print("➡️ Sending start command F1 01 …")
        await client.write_gatt_char(CTRL, b'\xF1\x01', response=True)

        print("🩺 Waiting for measurement data … (press Ctrl+C to stop)")
        await asyncio.sleep(90)   # listen for results

        # ---- Optional: cancel/stop ----
        # print("➡️ Sending cancel command F1 02 …")
        # await client.write_gatt_char(CTRL, b'\xF1\x02', response=True)

if __name__ == "__main__":
    asyncio.run(main())
