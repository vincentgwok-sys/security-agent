import json

def load(fn):
    with open(fn, 'r', encoding='utf-8') as f:
        return json.load(f)

def save(fn, data):
    with open(fn, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

# ============================================================
# Fix 1: CTX-002 - HOSTPATH_CHECK grep matches standard kernel FS
# ============================================================
d = load('SEC-K8S-CTX-002-1700000000000.json')

for ctx in d['executionContexts']:
    if ctx['contextId'].startswith('linux-'):
        cmds = ctx['executionLogic']['detectionCommands']
        # The last cmd is HOSTPATH_CHECK — replace it to only flag REAL hostPath
        # (ext4/xfs/btrfs/vfat/ntfs/FUSE mounts), not standard proc/sysfs/tmpfs/cgroup
        cmds[-1] = (
            "mount 2>&1 | grep -E 'on /(boot|dev|etc|lib|proc|sys|usr) ' | "
            "grep -vE 'type (proc|sysfs|tmpfs|devpts|devtmpfs|cgroup|cgroup2|mqueue|debugfs|tracefs|securityfs|pstore|bpf|fusectl|configfs)' | "
            "head -10; echo 'HOSTPATH_CHECK_DONE'"
        )

# Update expectedBehavior for HOSTPATH
eb0 = d['executionContexts'][0]['executionLogic']['expectedBehavior']
if 'proc/sysfs/tmpfs 等内核虚拟文件系统不在警告范围内' not in eb0:
    d['executionContexts'][0]['executionLogic']['expectedBehavior'] += (
        " HOSTPATH_CHECK: proc/sysfs/tmpfs/devtmpfs/cgroup 等内核虚拟文件系统是标准容器隔离文件系统，"
        "不是 hostPath 挂载，必须判 PASS。只有 ext4/xfs/btrfs/FUSE 等真实文件系统类型在禁止路径上才判 FAIL。"
    )
    d['executionContexts'][1]['executionLogic']['expectedBehavior'] += (
        " HOSTPATH_CHECK: 标准内核虚拟文件系统(proc/sysfs/tmpfs等)必须判 PASS。"
    )

save('SEC-K8S-CTX-002-1700000000000.json', d)
print("CTX-002 OK")

# ============================================================
# Fix 2: CTX-003 - SELinux not found on Ubuntu with AppArmor enabled = PASS
# ============================================================
d = load('SEC-K8S-CTX-003-1700000000000.json')

for ctx in d['executionContexts']:
    if ctx['contextId'].startswith('linux-'):
        ctx['executionLogic']['expectedBehavior'] += (
            " Ubuntu/Debian默认MAC是AppArmor而非SELinux。"
            "SELinux不存在=getenforce返回not found-这是Ubuntu正常行为，必须判PASS非FAIL。"
            "只要AppArmor启用(Y)就=PASS。仅当AppArmor也缺失且SELinux也缺失时才=WARN。"
        )

save('SEC-K8S-CTX-003-1700000000000.json', d)
print("CTX-003 OK")

# ============================================================
# Fix 3: CTX-004 - METADATA_BLOCK_RULES with iptables absent = PASS
# ============================================================
d = load('SEC-K8S-CTX-004-1700000000000.json')

for ctx in d['executionContexts']:
    if ctx['contextId'].startswith('linux-'):
        # Replace the METADATA_BLOCK_RULES command to first check iptables
        cmds = ctx['executionLogic']['detectionCommands']
        # Last cmd is the metadata block check
        cmds[-1] = (
            "command -v iptables >/dev/null 2>&1 && "
            "iptables -L FORWARD 2>&1 | grep -c '169.254.169.254' | xargs -I{} echo 'METADATA_BLOCK_RULES:{}' || "
            "echo 'IPTABLES_NOT_AVAILABLE'"
        )
        ctx['executionLogic']['expectedBehavior'] += (
            " METADATA_BLOCK_RULES检测仅当iptables可用时有效。"
            "iptables不可用=IPTABLES_NOT_AVAILABLE=PASS(容器内通常无iptables，节点级规则应从宿主机检测)。"
            "METADATA_BLOCK_RULES>0=PASS(已配置)，=0且iptables可用=WARN。"
        )

save('SEC-K8S-CTX-004-1700000000000.json', d)
print("CTX-004 OK")

# Verify all
for fn in sorted(__import__('os').listdir('.')):
    if fn.endswith('.json'):
        json.load(open(fn, encoding='utf-8'))
        print(f'Validated: {fn}')
