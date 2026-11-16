#!/usr/bin/env python3
"""Turns Adblock-Plus-style lists into three compact files Feather parses at startup.

  net_rules.txt         host<TAB>path-pattern<TAB>flags     (flags: t=third-party only, e=exception)
  cosmetic_generic.txt  one CSS selector per line
  cosmetic_domain.txt   comma-separated-hosts<TAB>selector

Only rules Feather can honour are kept: host-anchored network rules (||host^ or ||host/path)
without options Android's WebView can't tell us about, and simple element-hiding selectors.
Usage: build_filters.py OUT_DIR [--hosts hosts_file] list1.txt [list2.txt ...]
  blocklist.txt in OUT_DIR is rebuilt from: its current contents + --hosts + pure ||host^ rules (sorted, unique).
"""
import re, sys, os

args = sys.argv[1:]
out_dir = args.pop(0)
hosts_files = []
while "--hosts" in args:
    i = args.index("--hosts"); hosts_files.append(args[i + 1]); del args[i:i + 2]
lists = args
OK_OPTS = {"third-party", "3p", "~third-party", "script", "image", "stylesheet", "xmlhttprequest",
           "subdocument", "media", "font", "object", "important", "other"}
HOST_RE = re.compile(r"^\|\|([a-z0-9][a-z0-9.\-]*[a-z0-9])(.*)$")
BAD_SEL = re.compile(r":-abp-|:has-text|:xpath|:matches-|:contains|:upward|:remove|:style|\+js|:nth-ancestor|:min-text|:watch-attr|:others|:matches-media")
SIMPLE_SEL = re.compile(r"^[#.\[a-zA-Z][^{}\\]*$")

net, generic, domain = set(), set(), set()
host_block = set()
for path in lists:
    with open(path, encoding="utf-8", errors="ignore") as f:
        for raw in f:
            line = raw.strip()
            if not line or line[0] in "![" or line.startswith("# "):
                continue
            # ---- cosmetic
            if "##" in line and "#@#" not in line and "#?#" not in line and "#$#" not in line:
                hosts, sel = line.split("##", 1)
                if BAD_SEL.search(sel) or not SIMPLE_SEL.match(sel) or len(sel) > 200:
                    continue
                if "'" in sel or "\n" in sel:
                    continue
                if hosts == "":
                    generic.add(sel)
                else:
                    hs = [h for h in hosts.split(",") if h and not h.startswith("~") and "*" not in h and "/" not in h]
                    if hs:
                        domain.add(",".join(hs) + "\t" + sel)
                continue
            if "#@#" in line or "#?#" in line or "#$#" in line or "#%#" in line:
                continue
            # ---- network
            exception = line.startswith("@@")
            if exception:
                line = line[2:]
            opts = ""
            if "$" in line:
                line, opts = line.rsplit("$", 1)
            flags = ""
            if exception:
                flags += "e"
            if opts:
                olist = opts.split(",")
                if any(o.split("=")[0].lstrip("~") not in {x.lstrip("~") for x in OK_OPTS} or "=" in o for o in olist):
                    continue
                if "third-party" in olist or "3p" in olist:
                    flags += "t"
                elif "~third-party" in olist or "~3p" in olist:
                    continue
            m = HOST_RE.match(line)
            if not m:
                continue
            host, rest = m.group(1), m.group(2)
            if rest in ("", "^", "|", "^|"):
                pat = ""
            elif rest.startswith("^"):
                pat = rest[1:]
                if pat == "":
                    pat = ""
            elif rest.startswith("/") or rest.startswith("*"):
                pat = rest
            else:
                continue  # e.g. ||example.com.au^ forms we can't express
            pat = pat.rstrip("|")
            if " " in pat or "\t" in pat:
                continue
            if pat == "" and flags == "":
                host_block.add(host)       # plain ||host^ -> goes in the compact host list
            else:
                net.add(f"{host}\t{pat}\t{flags}")

os.makedirs(out_dir, exist_ok=True)
def w(name, rows):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(rows)) + "\n")
# merge host sources
existing = os.path.join(out_dir, "blocklist.txt")
if os.path.exists(existing):
    host_block |= {l.strip() for l in open(existing, encoding="utf-8") if l.strip()}
for hf in hosts_files:
    for l in open(hf, encoding="utf-8", errors="ignore"):
        l = l.strip().lower()
        if l.startswith("0.0.0.0 ") or l.startswith("127.0.0.1 "):
            l = l.split()[1]
        if l and not l.startswith("#") and re.match(r"^[a-z0-9][a-z0-9.\-]*$", l) and "." in l and l not in ("0.0.0.0", "localhost"):
            host_block.add(l)
w("blocklist.txt", host_block)
w("net_rules.txt", net)
# Cap generic selectors so injection stays cheap: prefer plain class/id selectors
gen = sorted(s for s in generic if re.match(r"^[#.][A-Za-z0-9_\-]+$", s))[:4000]
w("cosmetic_generic.txt", gen)
w("cosmetic_domain.txt", domain)
print(f"hosts={len(host_block)} net={len(net)} generic={len(gen)}/{len(generic)} domain={len(domain)}", file=sys.stderr)
