"""Azure sign-in and Resource Manager (virtual machines, disks, snapshots) for the "UT Azure VMs" agent.

    /cloud-azure/login/<tenant>/oauth2/v2.0/token      client-credentials sign-in (any made-up secret is accepted)
    /cloud-azure/arm/subscriptions/<id>/...            Resource Manager: VMs, instance view, start/deallocate/powerOff/
                                                       restart (202 + Azure-AsyncOperation), disks, snapshots (PUT)
    /cloud-azure/arm/operations/<id>                   the status document of a long-running operation (Succeeded)
"""
import re
import uuid

SEED = {"cloud_azure": {"vms": {
    "ut-invoice-vm": {"group": "rg-ut-bots", "location": "westeurope", "size": "Standard_B2s", "os": "Windows", "power": "running",
                      "tags": {"Team": "Finance"}},
    "ut-report-vm": {"group": "rg-ut-bots", "location": "westeurope", "size": "Standard_D2s_v5", "os": "Linux", "power": "deallocated",
                     "tags": {"Team": "Finance"}},
    "ut-hr-vm": {"group": "rg-ut-hr", "location": "northeurope", "size": "Standard_B1s", "os": "Linux", "power": "running", "tags": {}},
}, "snapshots": {}}}

POWER = {"running": "VM running", "deallocated": "VM deallocated", "stopped": "VM stopped"}


def handle(h, m, path, q):
    st = core.STATE.setdefault("cloud_azure", {"vms": {}, "snapshots": {}})
    base = "http://" + h.headers.get("Host", "127.0.0.1") + "/cloud-azure/arm"
    if re.match(r"/cloud-azure/login/[^/]+/oauth2/v2.0/token$", path) and m == "POST":
        b = h.body()
        if not b.get("client_secret"):
            return h.send(401, {"error": "invalid_client", "error_description": "No client secret"})
        return h.send(200, {"token_type": "Bearer", "expires_in": 3600, "access_token": "ut-azure-" + uuid.uuid4().hex[:12]})
    if not path.startswith("/cloud-azure/arm/"):
        raise KeyError(path)
    if not h.headers.get("Authorization", "").startswith("Bearer ut-azure-"):
        return h.send(401, {"error": {"code": "InvalidAuthenticationToken", "message": "No valid token"}})
    p = path[len("/cloud-azure/arm"):]
    if p.startswith("/operations/"):
        return h.send(200, {"status": "Succeeded"})
    ms = re.match(r"/subscriptions/([^/]+)(/.*)?$", p)
    if not ms:
        raise KeyError(path)
    sub, rest = ms.group(1), ms.group(2) or ""

    def vm_json(name, v):
        rid = "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Compute/virtualMachines/%s" % (sub, v["group"], name)
        return {"id": rid, "name": name, "location": v["location"], "tags": v["tags"],
                "properties": {"vmId": "0000-" + name, "provisioningState": "Succeeded", "hardwareProfile": {"vmSize": v["size"]},
                               "storageProfile": {"osDisk": {"osType": v["os"], "managedDisk": {
                                   "id": "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Compute/disks/%s_OsDisk" % (sub, v["group"], name)}}}}}

    with core.LOCK:
        vms = st["vms"]
        if rest == "":
            return h.send(200, {"id": "/subscriptions/" + sub, "subscriptionId": sub, "displayName": "UT subscription", "state": "Enabled"})
        ml = re.match(r"(?:/resourceGroups/([^/]+))?/providers/Microsoft.Compute/virtualMachines$", rest)
        if ml and m == "GET":
            return h.send(200, {"value": [vm_json(n, v) for n, v in vms.items() if not ml.group(1) or v["group"] == ml.group(1)]})
        mv = re.match(r"/resourceGroups/([^/]+)/providers/Microsoft.Compute/virtualMachines/([^/]+)(/[A-Za-z]+)?$", rest)
        if mv:
            g, name, op = mv.groups()
            v = vms.get(name)
            if v is None or v["group"] != g:
                return h.send(404, {"error": {"code": "ResourceNotFound", "message": "The Resource '%s' under resource group '%s' was not found." % (name, g)}})
            if op is None and m == "GET":
                return h.send(200, vm_json(name, v))
            if op == "/instanceView" and m == "GET":
                return h.send(200, {"statuses": [
                    {"code": "ProvisioningState/succeeded", "level": "Info", "displayStatus": "Provisioning succeeded", "time": "2026-11-20T08:00:00Z"},
                    {"code": "PowerState/" + v["power"], "level": "Info", "displayStatus": POWER.get(v["power"], v["power"])}]})
            if m == "POST" and op in ("/start", "/deallocate", "/powerOff", "/restart"):
                v["power"] = {"/start": "running", "/deallocate": "deallocated", "/powerOff": "stopped", "/restart": "running"}[op]
                v["last_op"] = op[1:] + ("+skipShutdown" if q.get("skipShutdown") == "true" else "")
                return h.send(202, None, headers={"Azure-AsyncOperation": base + "/operations/" + uuid.uuid4().hex, "Retry-After": "1"})
        md = re.match(r"/resourceGroups/([^/]+)/providers/Microsoft.Compute/disks/([^/]+)$", rest)
        if md and m == "GET":
            return h.send(200, {"id": rest and "/subscriptions/%s%s" % (sub, rest), "name": md.group(2), "location": "westeurope",
                                "properties": {"diskSizeGB": 128}})
        mn = re.match(r"/resourceGroups/([^/]+)/providers/Microsoft.Compute/snapshots/([^/]+)$", rest)
        if mn and m == "PUT":
            b = h.body()
            rid = "/subscriptions/%s%s" % (sub, rest)
            st["snapshots"][mn.group(2)] = {"source": b.get("properties", {}).get("creationData", {}).get("sourceResourceId"), "tags": b.get("tags")}
            return h.send(201, {"id": rid, "name": mn.group(2), "location": b.get("location"), "properties": {"provisioningState": "Succeeded"}})
    raise KeyError(path)


ROUTES = [("/cloud-azure/", handle, "cloud_azure")]
