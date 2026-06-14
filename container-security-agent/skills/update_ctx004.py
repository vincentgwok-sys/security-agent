import json

with open('SEC-K8S-CTX-004-1700000000000.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

# ===== Update Debian context =====
ctx = d['executionContexts'][0]
cmds = ctx['executionLogic']['detectionCommands']

# Internal lateral movement probes (insert after ss -tlnp at index 9)
internal_cmds = [
    "echo '=== INTERNAL_LATERAL_PROBE ==='; echo 'RT_COUNT:'$(ip route show 2>&1 | grep -cE '10\\.|172\\.1[6-9]\\.|172\\.2[0-9]\\.|172\\.3[0-1]\\.|192\\.168\\.')",
    "for gw in 10.0.0.1 10.0.1.1 172.16.0.1 192.168.0.1; do curl --connect-timeout 2 -s http://$gw:80 >/dev/null 2>&1 && echo 'GW_REACHABLE:'$gw:80 || true; curl --connect-timeout 2 -sk https://$gw:443 >/dev/null 2>&1 && echo 'GW_REACHABLE:'$gw:443 || true; done; echo 'GW_PROBE_DONE'",
    "ip neigh show 2>&1 | head -10; echo 'NEIGHBOR_COUNT:'$(ip neigh show 2>&1 | grep -c 'REACHABLE\\|STALE\\|DELAY')",
    "getent hosts kubernetes.default.svc.cluster.local 2>&1; getent hosts kube-dns.kube-system.svc.cluster.local 2>&1; echo 'SVC_DNS_DONE'",
]

for cmd in reversed(internal_cmds):
    cmds.insert(9, cmd)

# Update expectedBehavior
ctx['executionLogic']['expectedBehavior'] += (
    " INTERNAL_NETWORK: 供外部开发者使用的容器绝对不允许访问内网其他网元。"
    "RT_COUNT>0=WARN(内网路由存在但未必可达，需结合curl检测)。"
    "GW_REACHABLE=FAIL(内网网关/端点可达—横向移动风险)。"
    "NEIGHBOR_COUNT>0=WARN(可发现局域网内其他主机)。"
    "curl exit=7/28=PASS(NetworkPolicy生效阻断内网)。curl exit=0=FAIL。"
    "SVC_DNS能解析=WARN(仅信息收集)，DNS解析+curl可达=FAIL。"
)

# ===== Update Alpine context =====
ctx2 = d['executionContexts'][1]
cmds2 = ctx2['executionLogic']['detectionCommands']

alpine_cmds = [
    "echo '=== INTERNAL_LATERAL_PROBE ==='; echo 'RT_COUNT:'$(ip route show 2>&1 | grep -cE '10\\.|172\\.1[6-9]\\.|172\\.2[0-9]\\.|172\\.3[0-1]\\.|192\\.168\\.')",
    "for gw in 10.0.0.1 10.0.1.1 172.16.0.1 192.168.0.1; do curl --connect-timeout 2 -s http://$gw:80 >/dev/null 2>&1 && echo 'GW_REACHABLE:'$gw:80 || true; curl --connect-timeout 2 -sk https://$gw:443 >/dev/null 2>&1 && echo 'GW_REACHABLE:'$gw:443 || true; done; echo 'GW_PROBE_DONE'",
    "ip neigh show 2>&1 | head -10; echo 'NEIGHBOR_COUNT:'$(ip neigh show 2>&1 | grep -c 'REACHABLE\\|STALE\\|DELAY')",
]

for cmd in reversed(alpine_cmds):
    cmds2.insert(9, cmd)

ctx2['executionLogic']['expectedBehavior'] += (
    " INTERNAL_NETWORK: RT_COUNT>0=WARN, GW_REACHABLE=FAIL,"
    " NEIGHBOR_COUNT>0=WARN. curl exit=7/28=PASS, exit=0=FAIL."
)

with open('SEC-K8S-CTX-004-1700000000000.json', 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)

json.load(open('SEC-K8S-CTX-004-1700000000000.json', encoding='utf-8'))
print('CTX-004 updated')
print(f'Debian cmds: {len(d["executionContexts"][0]["executionLogic"]["detectionCommands"])}')
print(f'Alpine cmds: {len(d["executionContexts"][1]["executionLogic"]["detectionCommands"])}')
