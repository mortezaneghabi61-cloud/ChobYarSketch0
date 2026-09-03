from __future__ import annotations

import trader
from global_sources import fetch_global_snapshot


def resilient_global_snapshot():
    return fetch_global_snapshot(trader.AUDIT)


# Keep the proven paper engine/risk gates unchanged; replace only the public
# global market-data dependency with the resilient credential-free source set.
trader.global_snapshot = resilient_global_snapshot


if __name__ == "__main__":
    trader.main()
