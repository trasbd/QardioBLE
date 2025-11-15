import asyncio
from bleak import BleakClient
import datetime
import os

RING_MAC = "30:33:00:00:31:3F"
UUID_WRITE   = "0000b002-0000-1000-8000-00805f9b34fb"
UUID_NOTIFY  = "0000b003-0000-1000-8000-00805f9b34fb"

# ----------------------------------------------------------
# OPTION: set the ring's time to NOW before starting replay
# ----------------------------------------------------------
SET_TIME = True   # <- set False to skip time-writing

def build_set_time_cmd(dt: datetime.datetime):
    # JL datetime format: YY MM DD HH mm SS
    year = dt.year - 2000
    return bytes([
        0xAB, 0x01, 0x00, 0x09,    # header
        0x3C, 0x92, 0x02, 0x06,    # command prefix
        year & 0xFF,
        dt.month,
        dt.day,
        dt.hour-2,
        dt.minute+15,
        dt.second
    ]).hex()

# ----------------------------------------------------------
# Original replay sequence from ring-bthci-4.txt
# ----------------------------------------------------------
FULL_SEQ_HEX = [
    "ab010003b8f0030220",
    "ab010003cca2020410",
    "ab0100057976020200e803",

    "ab0100093c92020100190b09161400",
    #build_set_time_cmd(datetime.datetime.now()),

    "ab010004bba1020e0001",
    "ab010003fc88026310",
    "ab010003ac8b026610",
    "ab010003cc8a026410",
    "ab010003cc8f026810",
    "ab010003ad1a051a10",
    "ab010003751b051a30",
    "ab010003ad1a051a10",
    "ab010003751b051a30",
    "ab010003ad1a051a10",
    "ab010003751b051a30",
    "ab010003ad1a051a10",
    "ab010003ad10050210",
    "ab0100039d12050510",  # <- known working HR enable
    "ab0100033d11050310",  # <- known working stream start
    "ab010003e510050330",
    "ab0100033d11050310",
    "ab0100030d13050410",
    "ab0100039d17050910",
    "ab0100036d17050a10",
    "ab0100035d15050d10",
    "ab010003cca2020410",
    "ab010003fca0020310",
    "ab010006372f060900030500",
    "ab0100036cae021610",
    "ab010006372f060900030500",
]

OUT_DIR = "ring_replay_logs"
os.makedirs(OUT_DIR, exist_ok=True)

def handle_notify(sender: int, data: bytes):
    ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    hexstr = data.hex(" ")
    print(f"[{ts}] NOTIFY: {hexstr}")

#    with open(os.path.join(OUT_DIR, "notifications.log"), "a") as f:
 #       f.write(f"{ts} {hexstr}\n")

async def main():
    print("🔗 Connecting to ring at", RING_MAC)
    async with BleakClient(RING_MAC, timeout=30.0) as client:
        print("✅ Connected")

        print("📡 Enabling notifications...")
        await client.start_notify(UUID_NOTIFY, handle_notify)
        await asyncio.sleep(0.5)

        print("\n🚀 Replaying captured write sequence...")

        # -----------------------------------------
        # Replay the remaining original commands
        # -----------------------------------------
        for idx, hex_value in enumerate(FULL_SEQ_HEX, start=1):
            payload = bytes.fromhex(hex_value)
            print(f"➡️ [{idx}/{len(FULL_SEQ_HEX)}] WRITE {hex_value}")
            await client.write_gatt_char(UUID_WRITE, payload)
            await asyncio.sleep(0.4)

        print("\n⏳ Waiting a bit for any trailing notifications...")
        await asyncio.sleep(5.0)

        print("🛑 Stopping notifications")
        await client.stop_notify(UUID_NOTIFY)

    print("✅ Done. Logs saved in:", OUT_DIR)

if __name__ == "__main__":
    asyncio.run(main())
