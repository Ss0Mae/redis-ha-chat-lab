"""raw 파일에서 runs.jsonl 을 다시 만든다(collect.py 수정 후). 배치가 실행 중인 세트에는 쓰지 말 것.
사용: python3 bench/recollect.py <set>"""
import glob, json, os, subprocess, sys
set_ = sys.argv[1]
rows = []
for f in sorted(glob.glob(f"results/{set_}/raw/*.experiment.json")):
    eid = os.path.basename(f)[:-len(".experiment.json")]
    d = json.load(open(f))
    name = d.get("name") or ""
    parts = name.split("/")
    # name: set/scenario/topology/rpsN/repN  또는 set/variant/scenario/topology/rpsN/repN
    if len(parts) == 5: scenario, rps, rep = parts[1], parts[3][3:], parts[4][3:]
    elif len(parts) == 6: scenario, rps, rep = parts[2], parts[4][3:], parts[5][3:]
    else: continue
    driver = "k6" if os.path.exists(f"results/{set_}/raw/{eid}.k6.json") else "app"
    out = subprocess.run([sys.executable, "bench/collect.py", set_, eid, scenario, rps, rep, driver], capture_output=True, text=True)
    if out.returncode == 0: rows.append(out.stdout.strip())
    else: print("skip", eid, out.stderr[-200:])
open(f"results/{set_}/runs.jsonl", "w").write("\n".join(rows) + "\n")
print(f"rewrote results/{set_}/runs.jsonl ({len(rows)} rows)")
