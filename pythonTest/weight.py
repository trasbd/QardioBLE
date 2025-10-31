import asyncio
from bleak import BleakClient
from secrets import SCALE_MAC

ADDR = SCALE_MAC

UUID_FFE1 = "0000ffe1-0000-1000-8000-00805f9b34fb"
UUID_WEIGHT = "00002a9d-0000-1000-8000-00805f9b34fb"
UUID_BODY = "00002a9c-0000-1000-8000-00805f9b34fb"
UUID_USER = "00002a9f-0000-1000-8000-00805f9b34fb"

def on_notify(sender, data):
    print(f"[{sender}] {data.hex('-')}")

async def main():
    while True:
        print("🔗 Connecting to scale...")
        try:
            async with BleakClient(ADDR, timeout=4.0) as client:
                print("✅ Connected")

                # Enable notifications
                for uuid in [UUID_FFE1, UUID_WEIGHT, UUID_BODY, UUID_USER]:
                    try:
                        await client.start_notify(uuid, on_notify)
                        print(f"→ subscribed {uuid}")
                    except Exception as e:
                        print(f"⚠️ skip {uuid}: {e}")

                # Send consent command to 0x2A9F (User Control Point)
                print("🧾 Sending consent command...")
                try:
                    # 1️⃣  Register new user (opcode 0x01, consent code 0x0000)
                    await client.write_gatt_char(UUID_USER, bytearray([0x02, 0x01, 0x00, 0x00]), response=True)
                    print("🟢 Sent User Consent (0x01-00-00)")
                except Exception as e:
                    print(f"⚠️ Consent write failed: {e}")

                print("🩻 Waiting for data...")
                while client.is_connected:
                    await asyncio.sleep(0.2)
        except Exception as e:
            print(f"⚠️ connect error: {e}")
        print("🔁 retrying in 3s...\n")
        await asyncio.sleep(3)

asyncio.run(main())
