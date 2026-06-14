<template>
  <div id="app-root">
    <nav class="navbar">
      <div class="nav-brand">
        <router-link to="/" class="brand-link">[Sec] 容器安全审计 Agent</router-link>
      </div>
      <div class="nav-links">
        <router-link to="/tasks" class="nav-link">任务列表</router-link>
        <router-link to="/create" class="nav-link">创建任务</router-link>
        <router-link to="/skills" class="nav-link">Skill 管理</router-link>
      </div>
    </nav>
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f5f5f5; color: #1f2937; }
.navbar { background: #1e293b; color: #fff; padding: 0 24px; display: flex; align-items: center; height: 56px; gap: 32px; }
.nav-brand { font-size: 18px; font-weight: 600; }
.brand-link { color: #fff; text-decoration: none; }
.nav-links { display: flex; gap: 20px; }
.nav-link { color: #cbd5e1; text-decoration: none; font-size: 14px; padding: 4px 0; border-bottom: 2px solid transparent; transition: all .2s; }
.nav-link:hover, .nav-link.router-link-active { color: #fff; border-bottom-color: #22c55e; }
.main-content { max-width: 1200px; margin: 0 auto; padding: 24px; }
button { cursor: pointer; }
.btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 18px; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; transition: all .2s; }
.btn-primary { background: #1d4ed8; color: #fff; }
.btn-primary:hover { background: #1e40af; }
.btn-success { background: #16a34a; color: #fff; }
.btn-success:hover { background: #15803d; }
.btn-secondary { background: #e5e7eb; color: #374151; }
.btn-secondary:hover { background: #d1d5db; }
.btn-danger { background: #dc2626; color: #fff; }
.btn-danger:hover { background: #b91c1c; }
.btn-sm { padding: 4px 10px; font-size: 12px; }
.card { background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,.08); margin-bottom: 16px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; color: #374151; margin-bottom: 4px; }
.form-group input, .form-group select, .form-group textarea { width: 100%; padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; }
.form-group input:focus, .form-group select:focus, .form-group textarea:focus { outline: none; border-color: #1d4ed8; box-shadow: 0 0 0 3px rgba(29,78,216,.1); }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
th { font-weight: 600; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: .05em; background: #f9fafb; }
tr:hover td { background: #f9fafb; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 8px; padding: 16px 0; }
.pagination button { padding: 6px 12px; border: 1px solid #d1d5db; border-radius: 6px; background: #fff; font-size: 13px; }
.pagination button:disabled { opacity: .5; cursor: not-allowed; }
.pagination button:hover:not(:disabled) { background: #f3f4f6; }
.pagination span { font-size: 13px; color: #6b7280; }
.loading { text-align: center; padding: 40px; color: #6b7280; font-size: 16px; }
.error-msg { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; padding: 12px 16px; border-radius: 8px; margin-bottom: 16px; font-size: 14px; }
.empty-state { text-align: center; padding: 60px 20px; color: #9ca3af; }
.empty-state .icon { font-size: 48px; margin-bottom: 12px; }
</style>
