import json
import os
import time
import urllib.parse
import urllib.request

BASE = os.environ.get("KUDU_CONSOLE_URL", "http://localhost:8088")
CLUSTER = os.environ.get("KUDU_CLUSTER", "")
TABLE = "crudtest.smoke_" + str(int(time.time()))
print("console:", BASE)
print("platform:", CLUSTER or "(active)")
print("table:", TABLE)


def call(method, path, params=None, body=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = None
    headers = {}
    if CLUSTER:
        headers["X-Kudu-Cluster"] = CLUSTER
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  -> " + str(extra)))
    if not cond:
        raise SystemExit(1)


# 0. 清掉上次失败留下的临时表
try:
    status, listing = call("GET", "/api/tables")
    for group in listing.get("dbs", []):
        for table in group.get("tables", []):
            if table["name"].startswith("crudtest.smoke_"):
                call("POST", "/api/table/drop", {"table": table["name"]}, {"confirm": table["name"]})
                print("cleaned leftover:", table["name"])
except Exception as exc:  # 清理失败不影响主流程
    print("cleanup skipped:", exc)


# 1. 建表（12 种类型 + hash 分区）
columns = [
    {"name": "id", "type": "INT32", "key": True, "nullable": False},
    {"name": "c_i8", "type": "INT8", "nullable": True},
    {"name": "c_i16", "type": "INT16", "nullable": True},
    {"name": "c_i64", "type": "INT64", "nullable": True},
    {"name": "c_str", "type": "STRING", "nullable": True},
    {"name": "c_vch", "type": "VARCHAR", "length": 16, "nullable": True},
    {"name": "c_bool", "type": "BOOL", "nullable": True},
    {"name": "c_f", "type": "FLOAT", "nullable": True},
    {"name": "c_d", "type": "DOUBLE", "nullable": True},
    {"name": "c_dec", "type": "DECIMAL", "precision": 12, "scale": 2, "nullable": True},
    {"name": "c_date", "type": "DATE", "nullable": True},
    {"name": "c_ts", "type": "UNIXTIME_MICROS", "nullable": True},
    {"name": "c_bin", "type": "BINARY", "nullable": True},
]
status, res = call("POST", "/api/table/create", body={
    "name": TABLE,
    "numReplicas": 1,
    "columns": columns,
    "hashPartitions": [{"columns": ["id"], "buckets": 2, "seed": 0}],
    "rangePartitionColumns": [],
    "rangePartitions": []
})
check("create table", status == 200, res)

status, detail = call("GET", "/api/table", {"table": TABLE})
check("describe table", status == 200 and len(detail["columns"]) == 13, detail)
check("describe primary key", detail["primaryKey"] == ["id"], detail.get("primaryKey"))
check("describe partition", detail["partition"]["hashPartitions"][0]["buckets"] == 2, detail.get("partition"))

# 2. 插入全类型行
row = {
    "id": 1, "c_i8": -8, "c_i16": 1600, "c_i64": 9007199254740993,
    "c_str": "hello", "c_vch": "varchar", "c_bool": True,
    "c_f": 1.5, "c_d": 2.25, "c_dec": "1234.56",
    "c_date": "2026-10-28", "c_ts": 1792478019006002,
    "c_bin": "aGVsbG8="
}
row2 = {"id": 2, "c_str": "world", "c_dec": "0.01", "c_bool": False}
status, res = call("POST", "/api/table/rows", {"table": TABLE},
                   {"op": "INSERT", "rows": [row, row2]})
check("insert rows", status == 200 and res["applied"] == 2 and res["failed"] == 0, res)

# 3. 回读校验
status, q = call("POST", "/api/table/query", {"table": TABLE}, {"limit": 10, "offset": 0})
check("query rows", status == 200 and q["returned"] == 2, q)
by_id = {r["id"]: r for r in q["rows"]}
r1 = by_id[1]
check("INT8", r1["c_i8"] == -8, r1["c_i8"])
check("INT16", r1["c_i16"] == 1600, r1["c_i16"])
check("INT64 大数转字符串", r1["c_i64"] == "9007199254740993", r1["c_i64"])
check("VARCHAR", r1["c_vch"] == "varchar", r1["c_vch"])
check("DECIMAL 保留 scale", r1["c_dec"] == "1234.56", r1["c_dec"])
check("DATE", r1["c_date"] == "2026-10-28", r1["c_date"])
check("UNIXTIME_MICROS", r1["c_ts"] == 1792478019006002, r1["c_ts"])
check("BINARY base64", r1["c_bin"] == "aGVsbG8=", r1["c_bin"])
check("NULL", by_id[2]["c_i8"] is None, by_id[2])

# 4. 过滤
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "id", "op": "EQ", "value": "2"}], "limit": 10})
check("filter EQ", status == 200 and q["returned"] == 1 and q["rows"][0]["id"] == 2, q)
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "id", "op": "IN", "values": ["1", "2"]}], "limit": 10})
check("filter IN", status == 200 and q["returned"] == 2, q)
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "c_str", "op": "PREFIX", "value": "hel"}], "limit": 10})
check("filter PREFIX", status == 200 and q["returned"] == 1 and q["clientFiltered"] is True, q)
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "c_i8", "op": "NE", "value": "1"}], "limit": 10})
check("filter NE", status == 200 and q["returned"] == 1, q)
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "c_i8", "op": "IS_NULL"}], "limit": 10})
check("filter IS_NULL", status == 200 and q["returned"] == 1 and q["rows"][0]["id"] == 2, q)

# 5. UPDATE / DELETE
status, res = call("POST", "/api/table/rows", {"table": TABLE},
                   {"op": "UPDATE", "rows": [{"id": 2, "c_str": "updated"}]})
check("update row", status == 200 and res["applied"] == 1, res)
status, q = call("POST", "/api/table/query", {"table": TABLE},
                 {"filters": [{"column": "id", "op": "EQ", "value": "2"}], "limit": 5})
check("update visible", q["rows"][0]["c_str"] == "updated", q["rows"][0])

status, res = call("POST", "/api/table/rows", {"table": TABLE},
                   {"op": "DELETE", "rows": [{"id": 2}]})
check("delete row", status == 200 and res["applied"] == 1, res)

# 6. 批量写入 + 分页
bulk = [{"id": 1000 + i, "c_str": "row-%04d" % i, "c_i64": i} for i in range(200)]
status, res = call("POST", "/api/table/rows", {"table": TABLE}, {"op": "UPSERT", "rows": bulk})
check("bulk upsert 200", status == 200 and res["applied"] == 200 and res["failed"] == 0, res)

seen = []
offset = 0
while True:
    status, q = call("POST", "/api/table/query", {"table": TABLE},
                     {"limit": 100, "offset": offset, "columns": ["id"]})
    seen.extend([r["id"] for r in q["rows"]])
    if not q["hasNext"]:
        break
    offset += 100
check("pagination covers all rows", len(seen) == 201 and len(set(seen)) == 201, (len(seen), len(set(seen))))

status, q = call("POST", "/api/table/query", {"table": TABLE}, {"limit": 100, "offset": 20000})
check("offset cap rejected", status == 400 and q["code"] == "VALIDATION_FAILED", (status, q))

# 7. 加列 / 删列
status, res = call("POST", "/api/table/columns/add", {"table": TABLE},
                   {"name": "c_added", "type": "STRING", "nullable": True, "defaultValue": "dflt"})
check("add column", status == 200, res)
status, detail = call("GET", "/api/table", {"table": TABLE})
check("add column visible", any(c["name"] == "c_added" for c in detail["columns"]), detail["columns"])
status, res = call("POST", "/api/table/columns/drop", {"table": TABLE}, {"column": "c_added"})
check("drop column", status == 200, res)
status, detail = call("GET", "/api/table", {"table": TABLE})
check("drop column visible", not any(c["name"] == "c_added" for c in detail["columns"]), detail["columns"])

# 8. 改副本数（预期 UNSUPPORTED + CLI 提示）
status, res = call("POST", "/api/table/replicas", {"table": TABLE}, {"replicas": 3})
check("replicas guarded", status in (200, 422), (status, res))
print("   replicas response:", status, res)

# 9. 重命名
new_table = TABLE.replace("smoke_", "smoke_r_")
status, res = call("POST", "/api/table/rename", {"table": TABLE}, {"newName": new_table})
check("rename", status == 200, res)
status, detail = call("GET", "/api/table", {"table": new_table})
check("renamed detail", status == 200 and detail["name"] == new_table, detail)
TABLE = new_table

# 10. 清空（异步任务）
status, job = call("POST", "/api/jobs/truncate", {"table": TABLE},
                   {"confirm": TABLE, "acknowledge": True})
check("truncate accepted", status == 200 and job["id"], job)
for _ in range(60):
    time.sleep(0.5)
    status, job = call("GET", "/api/jobs/" + job["id"])
    if job["state"] in ("SUCCEEDED", "FAILED", "CANCELED"):
        break
check("truncate succeeded", job["state"] == "SUCCEEDED", job)
check("truncate deleted rows", job["deleted"] >= 200, job)

status, q = call("POST", "/api/table/query", {"table": TABLE}, {"limit": 10})
check("table empty after truncate", q["returned"] == 0, q)

# 11. 删表（含确认校验）
status, res = call("POST", "/api/table/drop", {"table": TABLE}, {"confirm": "wrong"})
check("drop requires confirm", status == 400 and res["code"] == "CONFIRM_REQUIRED", (status, res))
status, res = call("POST", "/api/table/drop", {"table": TABLE}, {"confirm": TABLE})
check("drop table", status == 200, res)
status, res = call("GET", "/api/table", {"table": TABLE})
check("dropped table gone", status == 404, (status, res))

# 12. 按前缀删表（预览 + 异步任务）
PREFIX = "crudtest.ptest_" + str(int(time.time())) + "."


def create_simple(name):
    return call("POST", "/api/table/create", body={
        "name": name,
        "numReplicas": 1,
        "columns": [{"name": "id", "type": "INT32", "key": True, "nullable": False}],
        "hashPartitions": [{"columns": ["id"], "buckets": 2, "seed": 0}],
        "rangePartitionColumns": [],
        "rangePartitions": []
    })


for suffix in ("a", "b"):
    status, res = create_simple(PREFIX + suffix)
    check("create prefix table " + suffix, status == 200, res)

status, preview = call("GET", "/api/tables/prefix", {"prefix": PREFIX})
check("prefix preview", status == 200 and preview["count"] == 2, preview)
check("prefix preview lists tables", sorted(preview["tables"]) == [PREFIX + "a", PREFIX + "b"], preview)

status, res = call("POST", "/api/jobs/drop-prefix", {"prefix": PREFIX},
                   {"confirm": "wrong", "acknowledge": True})
check("prefix drop requires confirm", status == 400 and res["code"] == "CONFIRM_REQUIRED", (status, res))

status, job = call("POST", "/api/jobs/drop-prefix", {"prefix": PREFIX},
                   {"confirm": PREFIX, "acknowledge": True})
check("prefix drop accepted", status == 200 and job["type"] == "DROP_PREFIX", job)
for _ in range(60):
    time.sleep(0.5)
    status, job = call("GET", "/api/jobs/" + job["id"])
    if job["state"] in ("SUCCEEDED", "FAILED", "CANCELED"):
        break
check("prefix drop succeeded", job["state"] == "SUCCEEDED" and job["deleted"] == 2, job)

status, listing = call("GET", "/api/tables")
remaining = [t["name"] for group in listing.get("dbs", [])
             for t in group.get("tables", []) if t["name"].startswith(PREFIX)]
check("prefix tables gone", not remaining, remaining)

# 13. 备份测试动作（删数据 / 加列 / 加数据）
OPS_TABLE = "crudtest.optest_" + str(int(time.time()))
status, res = create_simple(OPS_TABLE)
check("create ops table", status == 200, res)

status, job = call("POST", "/api/test/run", body={
    "tables": [OPS_TABLE],
    "actions": {"deleteRows": True, "deleteCount": 1,
                "addRows": True, "addCount": 2,
                "addColumn": True, "columnType": "STRING", "columnMode": "REPLACE"}
})
check("test ops accepted", status == 200 and job["type"] == "TEST_MUTATE", job)
for _ in range(60):
    time.sleep(0.5)
    status, job = call("GET", "/api/jobs/" + job["id"])
    if job["state"] in ("SUCCEEDED", "FAILED", "CANCELED"):
        break
check("test ops succeeded", job["state"] == "SUCCEEDED", job)

status, q = call("POST", "/api/table/query", {"table": OPS_TABLE}, {"limit": 10})
check("test ops added rows", status == 200 and q["returned"] == 2, q)
status, detail = call("GET", "/api/table", {"table": OPS_TABLE})
check("test ops added column",
      any(c["name"].startswith("bk_test_") for c in detail["columns"]), detail["columns"])
status, res = call("POST", "/api/table/drop", {"table": OPS_TABLE}, {"confirm": OPS_TABLE})
check("drop ops table", status == 200, res)

# 14. 表比对（两张同结构表，异步任务）
def insert_rows(table, rows):
    return call("POST", "/api/table/rows", {"table": table}, {"op": "INSERT", "rows": rows})


CMP_LEFT = "crudtest.cmp_l_" + str(int(time.time()))
CMP_RIGHT = "crudtest.cmp_r_" + str(int(time.time()))
status, res = create_simple(CMP_LEFT)
check("create compare left", status == 200, res)
status, res = create_simple(CMP_RIGHT)
check("create compare right", status == 200, res)
status, res = insert_rows(CMP_LEFT, [{"id": 1}, {"id": 2}])
check("seed compare left", status == 200 and res["applied"] == 2, res)
status, res = insert_rows(CMP_RIGHT, [{"id": 1}, {"id": 2}])
check("seed compare right", status == 200 and res["applied"] == 2, res)

status, task = call("POST", "/api/compare", body={
    "leftTable": CMP_LEFT,
    "rightTable": CMP_RIGHT,
    "compareData": True,
    "maxRows": 1000,
    "sampleLimit": 5
})
check("compare accepted", status == 200 and task["id"], task)
for _ in range(120):
    time.sleep(0.5)
    status, task = call("GET", "/api/compare/" + task["id"])
    if task["state"] in ("SUCCEEDED", "FAILED", "CANCELED"):
        break
check("compare succeeded", task["state"] == "SUCCEEDED", task)
check("compare consistent", task["result"]["consistent"] is True
      and task["result"]["data"]["matched"] == 2, task.get("result"))

for table in (CMP_LEFT, CMP_RIGHT):
    status, res = call("POST", "/api/table/drop", {"table": table}, {"confirm": table})
    check("drop compare table " + table, status == 200, res)

print("\nALL SMOKE TESTS PASSED")
