"""AWS EC2 query API for the "UT AWS EC2" agent, under /cloud-ec2/ (separate from the real-world tests' /ec2/).

Made-up instances, snapshots and volumes in memory; signatures are accepted, not checked. State changes happen at once
(a stopped instance is "stopped" in the next answer), so waiting steps finish on their first check.
"""
import re
import uuid
from urllib.parse import parse_qs

NS = ' xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"'

SEED = {"cloud_ec2": {
    "instances": [
        {"id": "i-0a1b2c3d4e5f60001", "state": "running", "type": "t3.micro", "image": "ami-0abc12345678def01", "ip": "10.30.0.11",
         "zone": "eu-west-1a", "monitoring": "disabled", "tags": {"Name": "UT invoice bot", "Team": "Finance"}},
        {"id": "i-0a1b2c3d4e5f60002", "state": "stopped", "type": "t3.small", "image": "ami-0abc12345678def01", "ip": "10.30.0.12",
         "zone": "eu-west-1a", "monitoring": "disabled", "tags": {"Name": "UT report bot", "Team": "Finance"}},
        {"id": "i-0a1b2c3d4e5f60003", "state": "running", "type": "m6i.large", "image": "ami-0abc12345678def02", "ip": "10.30.0.13",
         "zone": "eu-west-1b", "monitoring": "enabled", "tags": {"Name": "UT hr bot", "Team": "HR"}},
    ],
    "snapshots": [
        {"id": "snap-0a1b2c3d4e5f60001", "volume": "vol-0a1b2c3d4e5f60001", "status": "completed", "description": "UT nightly copy",
         "size": 8, "tags": {"Team": "Finance"}},
    ],
    "volumes": [
        {"id": "vol-0a1b2c3d4e5f60001", "zone": "eu-west-1a", "size": 8, "type": "gp3", "status": "in-use", "instance": "i-0a1b2c3d4e5f60001"},
    ],
}}


def _hex():
    return uuid.uuid4().hex[:17]


def handle(h, m, path, q):
    st = core.STATE.setdefault("cloud_ec2", {"instances": [], "snapshots": [], "volumes": []})
    f = parse_qs(h.raw().decode("utf-8"))
    act = f.get("Action", [""])[0]

    def one(k, d=""):
        return f.get(k, [d])[0]

    def listed(prefix):
        return [v[0] for k, v in sorted(f.items(), key=lambda kv: int(kv[0].rsplit(".", 1)[1]) if kv[0].rsplit(".", 1)[-1].isdigit() else 0)
                if re.fullmatch(re.escape(prefix) + r"\.\d+", k)]

    def tags_in(prefix):
        out, i = {}, 1
        while prefix + ".%d.Key" % i in f:
            out[one(prefix + ".%d.Key" % i)] = one(prefix + ".%d.Value" % i)
            i += 1
        return out

    def filters():
        out, i = {}, 1
        while "Filter.%d.Name" % i in f:
            out[one("Filter.%d.Name" % i)] = listed("Filter.%d.Value" % i)
            i += 1
        return out

    def xml(body):
        return h.send(200, '<?xml version="1.0" encoding="UTF-8"?><%sResponse%s><requestId>ut-%s</requestId>%s</%sResponse>'
                      % (act, NS, uuid.uuid4().hex[:8], body, act), ctype="text/xml")

    def err(code, msg, status=400):
        return h.send(status, '<?xml version="1.0" encoding="UTF-8"?><Response><Errors><Error><Code>%s</Code><Message>%s</Message>'
                      '</Error></Errors><RequestID>ut</RequestID></Response>' % (code, msg), ctype="text/xml")

    def tagset(t):
        return "<tagSet>" + "".join("<item><key>%s</key><value>%s</value></item>" % kv for kv in t.items()) + "</tagSet>"

    def inst_xml(i):
        return ("<item><instanceId>%s</instanceId><imageId>%s</imageId><instanceState><code>16</code><name>%s</name></instanceState>"
                "<privateIpAddress>%s</privateIpAddress><instanceType>%s</instanceType><launchTime>2026-11-01T08:00:00.000Z</launchTime>"
                "<placement><availabilityZone>%s</availabilityZone></placement><monitoring><state>%s</state></monitoring>%s</item>"
                % (i["id"], i["image"], i["state"], i["ip"], i["type"], i["zone"], i["monitoring"], tagset(i["tags"])))

    with core.LOCK:
        insts = st["instances"]
        ids = listed("InstanceId")
        for x in ids:
            if not any(i["id"] == x for i in insts):
                return err("InvalidInstanceID.NotFound", "The instance ID '%s' does not exist" % x)
        pick = [i for i in insts if not ids or i["id"] in ids]
        if act == "DescribeRegions":
            return xml("<regionInfo><item><regionName>%s</regionName><regionEndpoint>ec2.ut</regionEndpoint></item></regionInfo>" % one("RegionName.1", "eu-west-1"))
        if act == "DescribeInstances":
            for name, vals in filters().items():
                if name.startswith("tag:"):
                    pick = [i for i in pick if i["tags"].get(name[4:]) in vals]
                elif name == "instance-state-name":
                    pick = [i for i in pick if i["state"] in vals]
                elif name == "instance-type":
                    pick = [i for i in pick if i["type"] in vals]
            return xml("<reservationSet>" + "".join("<item><reservationId>r-ut</reservationId><instancesSet>%s</instancesSet></item>" % inst_xml(i)
                                                    for i in pick) + "</reservationSet>")
        if act == "DescribeInstanceStatus":
            if one("IncludeAllInstances") != "true":
                pick = [i for i in pick if i["state"] == "running"]
            return xml("<instanceStatusSet>" + "".join(
                "<item><instanceId>%s</instanceId><availabilityZone>%s</availabilityZone><instanceState><name>%s</name></instanceState>"
                "<systemStatus><status>%s</status></systemStatus><instanceStatus><status>%s</status></instanceStatus></item>"
                % (i["id"], i["zone"], i["state"], *(("ok", "ok") if i["state"] == "running" else ("not-applicable", "not-applicable")))
                for i in pick) + "</instanceStatusSet>")
        if act in ("StartInstances", "StopInstances", "TerminateInstances", "RebootInstances"):
            rows = ""
            for i in pick:
                before = i["state"]
                if before == "terminated":
                    return err("IncorrectInstanceState", "The instance '%s' is terminated" % i["id"])
                if act == "StartInstances":
                    i["state"] = "running"
                elif act == "StopInstances":
                    i["state"] = "stopped"
                elif act == "TerminateInstances":
                    i["state"] = "terminated"
                else:
                    i["reboots"] = i.get("reboots", 0) + 1
                rows += ("<item><instanceId>%s</instanceId><previousState><name>%s</name></previousState><currentState><name>%s</name></currentState></item>"
                         % (i["id"], before, i["state"]))
            return xml("<return>true</return>" if act == "RebootInstances" else "<instancesSet>%s</instancesSet>" % rows)
        if act in ("MonitorInstances", "UnmonitorInstances"):
            rows = ""
            for i in pick:
                i["monitoring"] = "enabled" if act == "MonitorInstances" else "disabled"
                rows += "<item><instanceId>%s</instanceId><monitoring><state>%s</state></monitoring></item>" % (
                    i["id"], "pending" if act == "MonitorInstances" else "disabling")
            return xml("<instancesSet>%s</instancesSet>" % rows)
        if act == "RunInstances":
            n = int(one("MaxCount", "1"))
            tags = tags_in("TagSpecification.1.Tag")
            new = []
            for _ in range(n):
                i = {"id": "i-" + _hex(), "state": "pending", "type": one("InstanceType"), "image": one("ImageId"),
                     "ip": "10.30.1.%d" % (len(insts) + 10), "zone": "eu-west-1a", "monitoring": "disabled", "tags": dict(tags)}
                insts.append(i)
                new.append(i)
            body = "<reservationId>r-ut</reservationId><instancesSet>%s</instancesSet>" % "".join(inst_xml(i) for i in new)
            for i in new:
                i["state"] = "running"
            return xml(body)
        if act == "CreateTags":
            res = listed("ResourceId")
            tags = tags_in("Tag")
            for i in insts:
                if i["id"] in res:
                    i["tags"].update(tags)
            return xml("<return>true</return>")
        snaps, vols = st["snapshots"], st["volumes"]
        if act == "CreateSnapshot":
            vid = one("VolumeId")
            v = next((x for x in vols if x["id"] == vid), None)
            if v is None:
                return err("InvalidVolume.NotFound", "The volume '%s' does not exist." % vid)
            s = {"id": "snap-" + _hex(), "volume": vid, "status": "pending", "description": one("Description"), "size": v["size"],
                 "tags": tags_in("TagSpecification.1.Tag")}
            snaps.append(s)
            body = "<snapshotId>%s</snapshotId><volumeId>%s</volumeId><status>pending</status><startTime>2026-11-23T09:00:00.000Z</startTime>" \
                   "<progress></progress><volumeSize>%d</volumeSize><description>%s</description>" % (s["id"], vid, v["size"], s["description"])
            s["status"] = "completed"
            return xml(body)
        if act == "DescribeSnapshots":
            pick = list(snaps)
            for name, vals in filters().items():
                if name == "volume-id":
                    pick = [s for s in pick if s["volume"] in vals]
                elif name.startswith("tag:"):
                    pick = [s for s in pick if s["tags"].get(name[4:]) in vals]
                elif name == "status":
                    pick = [s for s in pick if s["status"] in vals]
            return xml("<snapshotSet>" + "".join(
                "<item><snapshotId>%s</snapshotId><volumeId>%s</volumeId><status>%s</status><progress>100%%</progress>"
                "<startTime>2026-11-20T02:00:00.000Z</startTime><volumeSize>%d</volumeSize><description>%s</description>"
                "<encrypted>false</encrypted><ownerId>123456789012</ownerId>%s</item>"
                % (s["id"], s["volume"], s["status"], s["size"], s["description"], tagset(s["tags"])) for s in pick) + "</snapshotSet>")
        if act == "DeleteSnapshot":
            sid = one("SnapshotId")
            if not any(s["id"] == sid for s in snaps):
                return err("InvalidSnapshot.NotFound", "The snapshot '%s' does not exist." % sid)
            st["snapshots"] = [s for s in snaps if s["id"] != sid]
            return xml("<return>true</return>")
        if act == "CreateVolume":
            size = int(one("Size", "0") or 0)
            if not size and one("SnapshotId"):
                size = next((s["size"] for s in snaps if s["id"] == one("SnapshotId")), 8)
            v = {"id": "vol-" + _hex(), "zone": one("AvailabilityZone"), "size": size, "type": one("VolumeType", "gp3"), "status": "available", "instance": None}
            vols.append(v)
            return xml("<volumeId>%s</volumeId><size>%d</size><availabilityZone>%s</availabilityZone><status>creating</status>"
                       "<volumeType>%s</volumeType>" % (v["id"], size, v["zone"], v["type"]))
        if act in ("AttachVolume", "DetachVolume"):
            vid = one("VolumeId")
            v = next((x for x in vols if x["id"] == vid), None)
            if v is None:
                return err("InvalidVolume.NotFound", "The volume '%s' does not exist." % vid)
            if act == "AttachVolume":
                if v["instance"]:
                    return err("VolumeInUse", "%s is already attached to an instance" % vid)
                v["instance"], v["status"] = one("InstanceId"), "in-use"
                return xml("<volumeId>%s</volumeId><instanceId>%s</instanceId><device>%s</device><status>attaching</status>"
                           % (vid, v["instance"], one("Device")))
            if not v["instance"]:
                return err("IncorrectState", "Volume '%s' is in the 'available' state." % vid)
            inst, v["instance"], v["status"] = v["instance"], None, "available"
            return xml("<volumeId>%s</volumeId><instanceId>%s</instanceId><status>detaching</status>" % (vid, inst))
        return err("InvalidAction", "UT stand-in: %s is not supported" % act)


ROUTES = [("/cloud-ec2", handle, "cloud_ec2")]
