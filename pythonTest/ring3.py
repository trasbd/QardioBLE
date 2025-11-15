import asyncio
from datetime import datetime
from bleak import BleakClient

ADDR = "30:33:00:00:31:3F"
UUID_WRITE = "0000b002-0000-1000-8000-00805f9b34fb"
UUID_NOTIFY = "0000b003-0000-1000-8000-00805f9b34fb"
LOGFILE = "ring_log.txt"

def h(x: str) -> bytes:
    return bytes.fromhex(x.replace(" ", ""))

def log_line(text: str):
    """Append text to the log with timestamp."""
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    line = f"[{ts}] {text}\n"
    print(line, end="")
    with open(LOGFILE, "a", encoding="utf-8") as f:
        f.write(line)

def on_notify(sender, data: bytearray):
    """Handle incoming notifications."""
    log_line(f"[notify] {data.hex('-')}")

async def main():
    log_line("=== New session ===")
    async with BleakClient(ADDR) as c:
        log_line("✅ Connected")
        await c.start_notify(UUID_NOTIFY, on_notify)

        cmds = [
            "ab010003ad1a051a10",
            "ab010003751b051a30",
            "ab010003ad10050210",
            "ab0100039d12050510",
            "ab0100033d11050310"
        ]




        for cmd in cmds:
            log_line(f"➡️  {cmd}")
            await c.write_gatt_char(UUID_WRITE, h(cmd), response=False)
            await asyncio.sleep(1.0)

        log_line("🩻 Listening for responses … (Ctrl+C to stop)")
        while True:
            await asyncio.sleep(1)

asyncio.run(main())
