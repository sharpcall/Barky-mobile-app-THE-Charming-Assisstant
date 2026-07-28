#!/usr/bin/env python3
"""Print Oracle hub instance status + last-30d usage/cost (no secrets printed)."""
import datetime
import os
from pathlib import Path

import oci

def load_env(path: Path) -> dict:
    env = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def main() -> None:
    env = load_env(Path(r"F:\To sort\Delete\barky\secrets\oci.env"))
    config = oci.config.from_file(os.path.expanduser(r"~\.oci\config"), "DEFAULT")
    print("region=", config.get("region"))
    ten = config.get("tenancy") or ""
    print("tenancy=", ten[:18] + "..." if len(ten) > 18 else ten)

    identity = oci.identity.IdentityClient(config)
    compute = oci.core.ComputeClient(config)
    vn = oci.core.VirtualNetworkClient(config)
    usage = oci.usage_api.UsageapiClient(config)

    comp = env.get("compartment_ocid") or config.get("tenancy")
    print("compartment=", (comp or "")[:22] + "...")

    print("=== INSTANCES ===")
    insts = compute.list_instances(compartment_id=comp).data
    for i in insts:
        print(
            f"name={i.display_name} state={i.lifecycle_state} "
            f"shape={i.shape} created={i.time_created}"
        )
        try:
            atts = compute.list_vnic_attachments(
                compartment_id=comp, instance_id=i.id
            ).data
            for a in atts:
                if not a.vnic_id:
                    continue
                v = vn.get_vnic(a.vnic_id).data
                print(f"  public_ip={v.public_ip} private_ip={v.private_ip}")
        except Exception as e:
            print("  vnic err", type(e).__name__, str(e)[:120])

    end = datetime.datetime.now(datetime.timezone.utc)
    start = end - datetime.timedelta(days=30)
    # Usage API wants whole-hour boundaries
    start = start.replace(minute=0, second=0, microsecond=0)
    end_day = (end + datetime.timedelta(days=1)).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    print("=== USAGE last 30d COST ===")
    try:
        details = oci.usage_api.models.RequestSummarizedUsagesDetails(
            tenant_id=config["tenancy"],
            time_usage_started=start,
            time_usage_ended=end_day,
            granularity="DAILY",
            query_type="COST",
            group_by=["service"],
        )
        resp = usage.request_summarized_usages(details)
        total = 0.0
        rows = []
        for item in resp.data.items or []:
            c = float(item.computed_amount or 0)
            total += c
            rows.append(
                (
                    item.service or "?",
                    c,
                    float(item.computed_quantity or 0),
                    item.unit or "",
                    item.currency or "",
                )
            )
        rows.sort(key=lambda x: -x[1])
        for s, c, q, u, cur in rows[:20]:
            print(f"  service={s} cost={c:.4f} {cur} qty={q:.4f} unit={u}")
        print(f"TOTAL_COST_approx={total:.4f}")
    except Exception as e:
        print("usage err", type(e).__name__, str(e)[:300])

    print("=== USAGE last 30d USAGE (qty) ===")
    try:
        details = oci.usage_api.models.RequestSummarizedUsagesDetails(
            tenant_id=config["tenancy"],
            time_usage_started=start,
            time_usage_ended=end_day,
            granularity="DAILY",
            query_type="USAGE",
            group_by=["service", "skuName"],
        )
        resp = usage.request_summarized_usages(details)
        rows = []
        for item in resp.data.items or []:
            q = float(item.computed_quantity or 0)
            if q <= 0:
                continue
            rows.append(
                (
                    item.service or "?",
                    item.sku_name or item.sku_part_number or "?",
                    q,
                    item.unit or "",
                    float(item.computed_amount or 0),
                )
            )
        rows.sort(key=lambda x: -x[2])
        for s, sku, q, u, c in rows[:25]:
            print(f"  {s} | {sku} qty={q:.4f} {u} cost={c:.4f}")
    except Exception as e:
        print("usage-qty err", type(e).__name__, str(e)[:300])

    print("=== TENANCY ===")
    try:
        t = identity.get_tenancy(config["tenancy"]).data
        print("tenancy_name=", t.name)
        print("home_region=", t.home_region_key)
    except Exception as e:
        print("tenancy err", type(e).__name__, str(e)[:200])

    # Credits / subscription (may lack permission)
    print("=== SUBSCRIPTION / CREDITS (if permitted) ===")
    try:
        # Organizations subscriptions API
        org = oci.onesubscription.OrganizationSubscriptionClient(config)
        subs = org.list_organization_subscriptions(compartment_id=config["tenancy"]).data
        for s in subs or []:
            print("org_sub", s)
    except Exception as e:
        print("org_sub n/a:", type(e).__name__, str(e)[:200])

    try:
        # Usage proxy rewards (Universal Credits)
        # fallback: print free tier note
        print(
            "Note: Free-trial remaining credit is often only in OCI Console "
            "(Billing & Cost Management → Payment Method / Trial)."
        )
    except Exception:
        pass

    print("DONE")


if __name__ == "__main__":
    main()
