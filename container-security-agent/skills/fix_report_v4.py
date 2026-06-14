import json

# Fix CTX-004: ip route show + SSH_AUTH_SOCK
with open('SEC-K8S-CTX-004-1700000000000.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

ctx = d['executionContexts'][0]
ctx['executionLogic']['expectedBehavior'] += (
    " ip route show仅信息收集,绝对不得判WARN/FAIL。"
    " SSH_AUTH_SOCK是SSH agent socket路径,不是明文凭据,判PASS。"
    " 环境变量仅含明文password/api_key/token/secret值时判WARN。"
)

ctx2 = d['executionContexts'][1]
ctx2['executionLogic']['expectedBehavior'] += " ip route show仅INFO。SSH_AUTH_SOCK=PASS。"

with open('SEC-K8S-CTX-004-1700000000000.json', 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)

# Verify CTX-005
try:
    with open('SEC-K8S-CTX-005-1700000000000.json', 'r', encoding='utf-8') as f:
        d5 = json.load(f)
    print('CTX-005 JSON OK, skillId:', d5.get('skillId'))
except Exception as e:
    print(f'CTX-005 JSON ERROR: {e}')

# Verify all
for fn in sorted(__import__('os').listdir('.')):
    if fn.endswith('.json'):
        json.load(open(fn, encoding='utf-8'))
        print(f'OK: {fn}')
print("Done")
