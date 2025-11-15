import datetime
from typing import List, Tuple


def jl_be_int(data: bytes) -> int:
    """
    Equivalent to w4.d.j() used internally by w4.d.f().
    Interprets a byte array as BIG-ENDIAN unsigned int.
    """
    return int.from_bytes(data, byteorder="big", signed=False)


def jl_timestamp_to_epoch(raw_seconds: int) -> int:
    """
    Equivalent to the timestamp conversion used in RWFit:

        epoch = (raw + 946684800) - localOffsetSeconds

    localOffsetSeconds = rawOffset + dstOffset
    """
    BASE_2000 = 946_684_800

    # match Java's TimeZone.getDefault().getRawOffset() and DST
    now_local = datetime.datetime.now().astimezone()
    offset = now_local.utcoffset() or datetime.timedelta(0)
    offset_seconds = int(offset.total_seconds())

    return raw_seconds + BASE_2000 # - offset_seconds


def parse_jl_hr_history_frame(frame: bytes) -> List[Tuple[datetime.datetime, int]]:
    """
    Parse ONE JL heart-rate history frame exactly like:

        if (b10 == -59) { ... }

    in RWFit's BleResultHelper.C()

    The Java code uses i10 = 3 → history entries start at index 3.
    Each entry is:

        [0..3] = timestamp (4 bytes BE)
        [4]    = HR (1 byte)
        [5]    = padding (ignored)

    Returns:
        List[(datetime_utc, bpm)]
    """

    entries: List[Tuple[datetime.datetime, int]] = []

    # RWFit begins parsing at index 3
    i = 3
    n = len(frame)

    while i + 6 <= n:
        # read 4-byte timestamp in big-endian (w4.d.f / w4.d.j)
        ts_raw = jl_be_int(frame[i:i+4])

        # convert timestamp exactly like RWFit
        epoch_seconds = jl_timestamp_to_epoch(ts_raw)
        dt_utc = datetime.datetime.fromtimestamp(epoch_seconds, tz=datetime.timezone.utc)

        # HR byte
        hr = frame[i + 4] & 0xFF

        # (frame[i+5] is a padding byte ignored in Java)

        # sanity filter (matches what you assumed and RWFit UI uses)
        if 30 <= hr <= 220:
            entries.append((dt_utc, hr))

        i += 6  # RWFit increments i10 = i11 + 2 → 4 + 1 + 1 = 6 bytes per record

    return entries
