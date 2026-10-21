"""Create a non-production Firebase configuration for CI compilation only."""

import json
from pathlib import Path


target = Path(__file__).resolve().parents[2] / "app" / "google-services.json"
if target.exists():
    raise SystemExit("Refusing to overwrite an existing Firebase configuration.")

config = {
    "project_info": {
        "project_number": "123456789012",
        "project_id": "demo-mihon-ci",
    },
    "client": [
        {
            "client_info": {
                "mobilesdk_app_id": f"1:123456789012:android:{index:016x}",
                "android_client_info": {"package_name": f"dev.vishkel.mihon{suffix}"},
            },
            "api_key": [{"current_key": "AIzaSy" + "0" * 33}],
        }
        for index, suffix in enumerate(("", ".dev", ".foss", ".debug", ".benchmark"), 1)
    ],
    "configuration_version": "1",
}
target.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
print("Prepared dummy Firebase configuration; no live backend is connected.")
