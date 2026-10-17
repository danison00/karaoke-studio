# Netlify Frontend-only Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converter o Karaokê Studio em um único projeto Angular na raiz, remover integralmente Java/Spring/Maven e deixá-lo pronto para deploy estático no Netlify.

**Architecture:** A aplicação será uma SPA Angular executada somente no navegador e distribuída pelo CDN do Netlify. Mídia local e SoundTouch continuam no cliente; YouTube continua pela IFrame Player API oficial; não haverá API, Functions ou runtime de servidor.

**Tech Stack:** Angular 22.2.3 standalone, TypeScript 6 strict, SCSS, Vitest 5, Node.js 26.12.0 via NVM, npm 11.20.0, SoundTouch AudioWorklet 2.1.1, Netlify static hosting.

**Spec:** `docs/superpowers/specs/2026-10-17-netlify-frontend-only-design.md`

## Estado da implementação — 2026-10-17

Implementação concluída no workspace. O frontend foi promovido para a raiz, o projeto foi renomeado para `karaoke-studio`, o contrato Netlify foi criado, a rota curinga foi adicionada e Java/Maven/backend foram removidos conforme o escopo. Verificação final: Node `v26.12.0`, npm `11.20.0`, `14/14` arquivos de teste, `48/48` testes, build em `dist/karaoke-studio/browser` e revisão independente sem achados.

## Global Constraints

- Trabalhar no workspace existente; ele não é um repositório Git. Não executar `git init`, não criar commits e não criar worktree.
- Usar Node.js `26.12.0`, registrado em `.nvmrc`; executar `nvm use 26.12.0` antes dos comandos npm quando necessário.
- Preservar todas as funções e os 47 testes Angular existentes; esta migração não redesenha UI, DSP ou adapters de mídia.
- Não criar Netlify Functions, SSR, API, banco, autenticação ou segredos de build.
- Não mover `frontend/node_modules`, `frontend/dist` ou `frontend/.angular`; recriá-los na raiz com npm/Angular.
- Remover totalmente `backend/`, Maven, Java, `/api/app-info` e instruções operacionais relacionadas.
- Preservar `especificacao_karaoke_studio_codex.md`, o design de 2026-10-17 e este plano.
- Manter o AudioWorklet em `public/audio-worklets/soundtouch-processor.js` e publicado no mesmo caminho absoluto.
- O pitch permanece indisponível para YouTube; não adicionar captura ou extração de áudio.
- Em qualquer remoção recursiva no Windows, resolver e verificar o caminho absoluto antes de remover; usar PowerShell de ponta a ponta.

### Nota de execução no sandbox do Codex

Se `npm run test:ci` falhar com `ENOENT` apontando para `AppData\Local\Packages\sandbox...\AC\Temp`, executar o comando com `TEMP` e `TMP` apontando para uma pasta temporária explícita dentro do workspace. Essa é uma adaptação do ambiente de execução, não uma mudança no projeto:

```powershell
$tempRoot = Join-Path (Resolve-Path '.').Path '.tmp-test'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
npm run test:ci
$exitCode = $LASTEXITCODE
Remove-Item -LiteralPath $tempRoot -Recurse -Force
exit $exitCode
```

## Instruções de execução para o modelo Lua

- Ler este plano e o design vinculado por inteiro antes de alterar arquivos.
- Executar as Tasks 1–7 em ordem, uma etapa por vez; não antecipar a remoção da Task 6.
- Tratar cada resultado `Expected` como condição de avanço. Em caso de divergência, parar, diagnosticar e corrigir antes de seguir.
- Não improvisar um substituto para o backend e não ampliar o escopo com SSR, Functions, serviços externos ou redesign.
- Antes de qualquer `Remove-Item`, conferir os caminhos impressos na Task 6 e limitar a remoção aos sete alvos explícitos.
- Ao concluir cada task, registrar o checkpoint com comandos executados e resultados observados; não alegar testes ou verificações manuais que não tenham sido feitos.

## File Structure

**Raiz Angular após a migração:**

- `angular.json` — workspace Angular com projeto `karaoke-studio`.
- `package.json` / `package-lock.json` — dependências, scripts e pacote `karaoke-studio`.
- `src/` — aplicação e testes já existentes, sem mudanças funcionais.
- `public/` — favicon e processor SoundTouch.
- `netlify.toml` — contrato de build, publicação, fallback da SPA e cabeçalho estático.
- `.nvmrc` — Node 26.12.0 para desenvolvimento e Netlify.
- `README.md` — execução e deploy frontend-only.
- `docs/arquitetura-e-limitacoes.md` — arquitetura de navegador/CDN.
- `docs/roteiro-teste-manual.md` — validação local e no domínio Netlify.

**Removidos:**

- `frontend/` depois da promoção validada.
- `backend/`, `pom.xml`, `.java-version`, `.mvn/`.
- `.superpowers/sdd/` e `docs/superpowers/plans/2026-10-16-karaoke-studio.md`.

## Review Focus

- Colisão durante a promoção: se qualquer destino (`src`, `public`, package/tsconfig) já existir, interromper antes de mover e não sobrescrever silenciosamente; teste na Task 2.
- Build renomeado: nenhuma referência `frontend:build:*` ou saída `dist/frontend` pode sobreviver; teste na Task 3.
- Asset DSP: o build precisa conter `dist/karaoke-studio/browser/audio-worklets/soundtouch-processor.js` não vazio; teste na Task 4.
- Rota profunda: `netlify.toml` deve reescrever `/*` para `/index.html` com status `200`, sem `force`, preservando assets reais; teste na Task 4.
- Roteamento Angular: `src/app/app.routes.ts` deve redirecionar caminhos desconhecidos para o shell; `src/app/app.routes.spec.ts` deve impedir regressão `NG04002` em URLs profundas.
- Limpeza incompleta: documentação ativa e arquivos operacionais não podem referenciar Spring, Maven, Java, backend, `/api/app-info` ou `localhost:8080`; teste na Task 5.

---

### Task 1: Registrar baseline antes da movimentação

**Files:**
- Read: `.nvmrc`
- Read: `frontend/package.json`
- Read: `frontend/angular.json`
- Test: `frontend/src/**/*.spec.ts`

**Interfaces:**
- Consumes: workspace atual Angular dentro de `frontend/`.
- Produces: evidência de que a aplicação passa antes da migração e inventário de destinos livres na raiz.

- [ ] **Step 1: Confirmar versões ativas**

Run from workspace root:

```powershell
nvm use 26.12.0
node --version
npm --version
```

Expected: Node `v26.12.0`; npm `11.20.0`.

- [ ] **Step 2: Executar a suíte Angular no local atual**

Run:

```powershell
npm run test:ci --prefix frontend
```

Expected: 13 test files and 47 tests pass; zero failures.

- [ ] **Step 3: Executar o build Angular no local atual**

Run:

```powershell
npm run build --prefix frontend
```

Expected: build succeeds and writes `frontend/dist/frontend/browser/index.html`.

- [ ] **Step 4: Testar previamente que os destinos da promoção estão livres**

Run:

```powershell
$destinations = @('.editorconfig', '.prettierrc', 'angular.json', 'package.json', 'package-lock.json', 'tsconfig.json', 'tsconfig.app.json', 'tsconfig.spec.json', 'src', 'public')
$collisions = $destinations | Where-Object { Test-Path -LiteralPath $_ }
if ($collisions) { throw "Destinos já existentes: $($collisions -join ', ')" }
```

Expected: command completes without exception. If it fails, stop and inspect; do not overwrite the listed targets.

- [ ] **Step 5: Registrar checkpoint sem commit**

Report: baseline frontend `47/47` green and no destination collisions. Do not initialize Git.

---

### Task 2: Promover o projeto Angular para a raiz

**Files:**
- Move: `frontend/.editorconfig` → `.editorconfig`
- Move: `frontend/.prettierrc` → `.prettierrc`
- Move: `frontend/angular.json` → `angular.json`
- Move: `frontend/package.json` → `package.json`
- Move: `frontend/package-lock.json` → `package-lock.json`
- Move: `frontend/tsconfig.json` → `tsconfig.json`
- Move: `frontend/tsconfig.app.json` → `tsconfig.app.json`
- Move: `frontend/tsconfig.spec.json` → `tsconfig.spec.json`
- Move: `frontend/src` → `src`
- Move: `frontend/public` → `public`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: baseline aprovado e destinos livres da Task 1.
- Produces: aplicação Angular executável na raiz, ainda com identificador interno temporário `frontend`.

- [ ] **Step 1: Validar os caminhos absolutos antes de mover**

Run:

```powershell
$workspaceRoot = (Resolve-Path '.').Path
$frontendRoot = (Resolve-Path 'frontend').Path
if (-not $frontendRoot.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'frontend fora do workspace' }
```

Expected: no exception; `frontend` resolves below the workspace root.

- [ ] **Step 2: Mover apenas fontes e configurações versionáveis**

Run:

```powershell
$moves = [ordered]@{
  'frontend/.editorconfig' = '.editorconfig'
  'frontend/.prettierrc' = '.prettierrc'
  'frontend/angular.json' = 'angular.json'
  'frontend/package.json' = 'package.json'
  'frontend/package-lock.json' = 'package-lock.json'
  'frontend/tsconfig.json' = 'tsconfig.json'
  'frontend/tsconfig.app.json' = 'tsconfig.app.json'
  'frontend/tsconfig.spec.json' = 'tsconfig.spec.json'
  'frontend/src' = 'src'
  'frontend/public' = 'public'
}
foreach ($entry in $moves.GetEnumerator()) {
  if (-not (Test-Path -LiteralPath $entry.Key)) { throw "Origem ausente: $($entry.Key)" }
  if (Test-Path -LiteralPath $entry.Value) { throw "Destino já existe: $($entry.Value)" }
  Move-Item -LiteralPath $entry.Key -Destination $entry.Value
}
```

Do not move `frontend/node_modules`, `frontend/dist`, `frontend/.angular`, `frontend/.gitignore` or `frontend/README.md`.

Expected: `src/app/app.ts`, `public/audio-worklets/soundtouch-processor.js`, `package.json` and `angular.json` exist at the workspace root.

- [ ] **Step 3: Substituir o `.gitignore` da raiz por regras adequadas ao Angular na raiz**

The resulting `.gitignore` must include, at minimum:

```gitignore
/dist
/node_modules
/.angular/cache
/coverage
.idea/
.vscode/*
!.vscode/settings.json
!.vscode/tasks.json
!.vscode/launch.json
!.vscode/extensions.json
!.vscode/mcp.json
npm-debug.log
.DS_Store
Thumbs.db
```

Remove obsolete entries for `frontend/node_modules`, `frontend/dist` and `backend/target`.

- [ ] **Step 4: Instalar dependências na raiz a partir do lockfile movido**

Run:

```powershell
npm ci
```

Expected: exit code 0 and root `node_modules/` created.

- [ ] **Step 5: Executar os testes na raiz**

Run:

```powershell
npm run test:ci
```

Expected: 13 test files and 47 tests pass from the workspace root.

- [ ] **Step 6: Executar o build na raiz**

Run:

```powershell
npm run build
```

Expected: build succeeds; temporary output remains `dist/frontend/browser` until Task 3.

- [ ] **Step 7: Registrar checkpoint sem commit**

Report: Angular sources promoted, dependency install succeeds, tests `47/47` green. Do not remove `frontend/` yet.

---

### Task 3: Renomear pacote e projeto Angular

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `angular.json`
- Test: Angular configuration and full frontend suite

**Interfaces:**
- Consumes: Angular executável na raiz da Task 2.
- Produces: package/project name `karaoke-studio` and output `dist/karaoke-studio/browser` used by Netlify.

- [ ] **Step 1: Escrever a verificação que deve falhar antes da renomeação**

Run:

```powershell
$package = Get-Content -Raw 'package.json' | ConvertFrom-Json
$angular = Get-Content -Raw 'angular.json' | ConvertFrom-Json
if ($package.name -ne 'karaoke-studio') { throw 'package ainda não renomeado' }
if (-not $angular.projects.'karaoke-studio') { throw 'projeto Angular ainda não renomeado' }
```

Expected: FAIL because both names are still `frontend`.

- [ ] **Step 2: Renomear o pacote npm**

In `package.json`, change only `"name": "frontend"` to `"name": "karaoke-studio"`. Preserve scripts, dependencies and `packageManager`.

- [ ] **Step 3: Atualizar o lockfile de forma determinística**

Run:

```powershell
npm install --package-lock-only --ignore-scripts
```

Expected: top-level `name` and `packages[""].name` in `package-lock.json` become `karaoke-studio`; dependency versions stay locked.

- [ ] **Step 4: Renomear o projeto em `angular.json`**

Make these exact structural changes:

- `projects.frontend` → `projects.karaoke-studio`.
- `serve.configurations.production.buildTarget` → `karaoke-studio:build:production`.
- `serve.configurations.development.buildTarget` → `karaoke-studio:build:development`.
- Preserve source root, builders, assets, styles, budgets and test builder unchanged.

- [ ] **Step 5: Executar a verificação de nomes novamente**

Run the PowerShell verification from Step 1 and additionally:

```powershell
$text = Get-Content -Raw 'angular.json'
if ($text -match 'frontend:build') { throw 'buildTarget antigo encontrado' }
```

Expected: all commands pass with no exception.

- [ ] **Step 6: Executar testes e build renomeado**

Run:

```powershell
npm run test:ci
npm run build
```

Expected: 47 tests pass; build writes `dist/karaoke-studio/browser/index.html`; `dist/frontend` is not recreated by this build.

- [ ] **Step 7: Registrar checkpoint sem commit**

Report: package, lockfile, Angular project and build output all use `karaoke-studio`.

---

### Task 4: Configurar deploy estático no Netlify

**Files:**
- Create: `netlify.toml`
- Create: `src/app/app.routes.spec.ts`
- Modify: `src/app/app.routes.ts`
- Read: `.nvmrc`
- Test: `netlify.toml` contract, Angular deep-route fallback and production build contents

**Interfaces:**
- Consumes: output `dist/karaoke-studio/browser` from Task 3.
- Produces: Netlify build command, publish directory, Node version, SPA fallback and security header.

- [ ] **Step 1: Executar a verificação de configuração ausente**

Run:

```powershell
if (-not (Test-Path -LiteralPath 'netlify.toml')) { throw 'netlify.toml ausente' }
```

Expected: FAIL because the file does not exist yet.

- [ ] **Step 2: Criar `netlify.toml` com o contrato exato**

```toml
[build]
  command = "npm run build"
  publish = "dist/karaoke-studio/browser"

[build.environment]
  NODE_VERSION = "26.12.0"

[[redirects]]
  from = "/*"
  to = "/index.html"
  status = 200

[[headers]]
  for = "/*"
  [headers.values]
    X-Content-Type-Options = "nosniff"
```

Do not add `force = true`, COEP, COOP, Netlify Functions or secrets.

- [ ] **Step 3: Verificar os valores do Netlify e a coerência com `.nvmrc`**

Run:

```powershell
$config = Get-Content -Raw 'netlify.toml'
$nodeVersion = (Get-Content -Raw '.nvmrc').Trim()
if ($nodeVersion -ne '26.12.0') { throw '.nvmrc inesperado' }
if ($config -notmatch 'command\s*=\s*"npm run build"') { throw 'build command inválido' }
if ($config -notmatch 'publish\s*=\s*"dist/karaoke-studio/browser"') { throw 'publish inválido' }
if ($config -notmatch 'NODE_VERSION\s*=\s*"26.12.0"') { throw 'Node do Netlify inválido' }
if ($config -notmatch 'from\s*=\s*"/\*"' -or $config -notmatch 'to\s*=\s*"/index.html"' -or $config -notmatch 'status\s*=\s*200') { throw 'fallback SPA inválido' }
```

Expected: no exception.

- [ ] **Step 4: Validar a saída estática necessária em produção**

Run:

```powershell
npm run build
$publish = Resolve-Path 'dist/karaoke-studio/browser'
$required = @('index.html', 'audio-worklets/soundtouch-processor.js')
foreach ($item in $required) {
  $path = Join-Path $publish $item
  if (-not (Test-Path -LiteralPath $path)) { throw "Asset ausente: $item" }
  if ((Get-Item -LiteralPath $path).Length -eq 0) { throw "Asset vazio: $item" }
}
```

Expected: build succeeds; both files exist and are non-empty.

- [ ] **Step 5: Confirmar que não há cabeçalhos incompatíveis com YouTube**

Run:

```powershell
$config = Get-Content -Raw 'netlify.toml'
if ($config -match 'Cross-Origin-Embedder-Policy|Cross-Origin-Opener-Policy') { throw 'Cabeçalho cross-origin incompatível encontrado' }
```

Expected: no exception.

- [ ] **Step 6: Escrever o teste de rota profunda antes da implementação**

Create `src/app/app.routes.spec.ts` with a test that finds the route with `path: '**'` and expects `redirectTo` to be the empty path. Run `npm run test:ci` with the workspace-local `TEMP`/`TMP` workaround when required.

Expected: FAIL because `routes` currently has no wildcard route.

- [ ] **Step 7: Implementar e verificar o fallback Angular**

Add `{ path: '**', redirectTo: '' }` to `src/app/app.routes.ts`. Run:

```powershell
npm run test:ci
npm run build
```

Expected: `14` test files and `48` tests pass; the build remains successful. A deep URL must load the shell without `NG04002` in the browser console.

- [ ] **Step 8: Registrar checkpoint sem commit**

Report: Netlify config, Angular deep-route fallback, build output and worklet asset verified.

---

### Task 5: Atualizar documentação para frontend-only

**Files:**
- Modify: `README.md`
- Modify: `docs/arquitetura-e-limitacoes.md`
- Modify: `docs/roteiro-teste-manual.md`
- Preserve: `especificacao_karaoke_studio_codex.md`
- Preserve: `docs/superpowers/specs/2026-10-17-netlify-frontend-only-design.md`
- Preserve: `docs/superpowers/plans/2026-10-17-netlify-frontend-only.md`

**Interfaces:**
- Consumes: root commands and Netlify contract from Tasks 2–4.
- Produces: one documented path for development, validation and deployment.

- [ ] **Step 1: Atualizar `README.md`**

Document only these workflows:

- Requirements: NVM, Node 26.12.0 and npm 11.20.0.
- Setup: `nvm use 26.12.0`, `npm ci`.
- Development: `npm start`, URL `http://localhost:4200`.
- Verification: `npm run test:ci`, `npm run build`.
- Netlify: connect the repository, build command `npm run build`, publish directory `dist/karaoke-studio/browser`; explain that `netlify.toml` already declares them.
- Product limits: local codecs, AudioContext interaction, SoundTouch artifacts/latency and pitch unavailable on YouTube.

Remove Java, Spring Boot, Maven, JAR, backend API and `localhost:8080` instructions.

- [ ] **Step 2: Atualizar `docs/arquitetura-e-limitacoes.md`**

Replace the backend section with Netlify CDN/static hosting. Preserve the local Web Audio graph and YouTube restrictions. State explicitly that no media is uploaded and no runtime server exists.

- [ ] **Step 3: Atualizar `docs/roteiro-teste-manual.md`**

Add a Netlify section that verifies:

- homepage loads on a deploy preview/production domain;
- a deep URL returns the Angular shell rather than 404;
- `/audio-worklets/soundtouch-processor.js` returns successfully;
- a local audio file still gets `DSP ativo` and pitch `0 → +2 → −3 → 0`;
- a valid YouTube URL uses the official player with pitch disabled.

- [ ] **Step 4: Testar a documentação ativa contra referências operacionais antigas**

Run:

```powershell
$activeDocs = @('README.md', 'docs/arquitetura-e-limitacoes.md', 'docs/roteiro-teste-manual.md')
$obsolete = Select-String -Path $activeDocs -Pattern 'Spring Boot|Maven|\bJava\b|/api/app-info|localhost:8080|backend/'
if ($obsolete) { $obsolete | Format-Table; throw 'Referências operacionais antigas encontradas' }
```

Expected: no matches and no exception.

- [ ] **Step 5: Registrar checkpoint sem commit**

Report: active docs describe only Angular/npm/Netlify and preserve honest platform limits.

---

### Task 6: Remover backend e resíduos da arquitetura anterior

**Files:**
- Delete: `backend/`
- Delete: `pom.xml`
- Delete: `.java-version`
- Delete: `.mvn/`
- Delete: `docs/superpowers/plans/2026-10-16-karaoke-studio.md`
- Delete: `.superpowers/sdd/`
- Delete: `frontend/` after validating promoted files

**Interfaces:**
- Consumes: root app green and documented from Tasks 2–5.
- Produces: workspace exclusivamente Angular, sem segunda cópia ou ferramentas Java/Maven.

- [ ] **Step 1: Verificar que a promoção está completa antes de remover origens**

Run:

```powershell
$required = @('package.json', 'package-lock.json', 'angular.json', 'src/app/app.ts', 'public/audio-worklets/soundtouch-processor.js', 'netlify.toml')
$missing = $required | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { throw "Arquivos promovidos ausentes: $($missing -join ', ')" }
```

Expected: no missing files.

- [ ] **Step 2: Resolver e validar cada destino destrutivo**

Run from workspace root with explicit targets:

```powershell
$workspaceRoot = (Resolve-Path '.').Path
$targets = @('backend', 'frontend', '.mvn', '.superpowers/sdd', 'pom.xml', '.java-version', 'docs/superpowers/plans/2026-10-16-karaoke-studio.md')
foreach ($target in $targets) {
  if (Test-Path -LiteralPath $target) {
    $resolved = (Resolve-Path -LiteralPath $target).Path
    $expected = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $target))
    if (-not $resolved.Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) { throw "Destino inesperado: $resolved" }
    Write-Output $resolved
  }
}
```

Expected: every printed path is inside the exact workspace `karaoke studio`.

- [ ] **Step 3: Remover os destinos autorizados usando PowerShell**

Use `Remove-Item -LiteralPath` for each explicit target. Use `-Recurse` only for the four directories (`backend`, `frontend`, `.mvn`, `.superpowers/sdd`). Do not use globs, environment-variable-computed roots or cross-shell deletion.

Expected: all seven targets from Step 2 no longer exist. Report that source files were intentionally removed and are recoverable only from external copies because this workspace has no Git history.

- [ ] **Step 4: Verificar ausência de código e ferramentas Java/Maven**

Run:

```powershell
$forbiddenPaths = @('backend', 'frontend', '.mvn', 'pom.xml', '.java-version')
$remaining = $forbiddenPaths | Where-Object { Test-Path -LiteralPath $_ }
if ($remaining) { throw "Resíduos encontrados: $($remaining -join ', ')" }
$sourceHits = rg -n --glob '!docs/superpowers/specs/**' --glob '!docs/superpowers/plans/**' --glob '!node_modules/**' --glob '!dist/**' --glob '!.angular/**' 'org\.springframework|SpringApplication|<artifactId>|/api/app-info|localhost:8080' .
if ($LASTEXITCODE -eq 0) { $sourceHits; throw 'Referências de backend encontradas' }
if ($LASTEXITCODE -gt 1) { throw 'rg falhou' }
```

Expected: no forbidden paths and `rg` exits 1 (no matches).

- [ ] **Step 5: Executar a suíte após a remoção**

Run:

```powershell
npm run test:ci
npm run build
```

Expected: 14 test files / 48 tests pass; production build succeeds.

- [ ] **Step 6: Registrar checkpoint sem commit**

Report exactly what was removed and note that no Git repository was initialized.

---

### Task 7: Verificação final de entrega estática

**Files:**
- Verify: all root project files
- Verify: `dist/karaoke-studio/browser/**`
- Verify: `netlify.toml`

**Interfaces:**
- Consumes: frontend-only workspace from Task 6.
- Produces: evidence package ready for the Lua model's handoff and later Netlify connection.

- [ ] **Step 1: Fazer instalação limpa final**

Run:

```powershell
npm ci
```

Expected: exit code 0 using root `package-lock.json`.

- [ ] **Step 2: Executar verificação automatizada final**

Run:

```powershell
npm run test:ci
npm run build
```

Expected: 48 tests pass; build succeeds without warnings that exceed Angular budgets.

- [ ] **Step 3: Verificar o pacote estático final**

Run:

```powershell
$publish = Resolve-Path 'dist/karaoke-studio/browser'
$index = Get-Item -LiteralPath (Join-Path $publish 'index.html')
$worklet = Get-Item -LiteralPath (Join-Path $publish 'audio-worklets/soundtouch-processor.js')
$chunks = Get-ChildItem -LiteralPath $publish -Filter '*.js'
if ($index.Length -eq 0) { throw 'index.html vazio' }
if ($worklet.Length -eq 0) { throw 'AudioWorklet vazio' }
if (-not $chunks) { throw 'chunks JavaScript ausentes' }
```

Expected: non-empty index, processor and JavaScript chunks.

- [ ] **Step 4: Verificar a árvore final e nomes antigos**

Run:

```powershell
$package = Get-Content -Raw 'package.json' | ConvertFrom-Json
$angular = Get-Content -Raw 'angular.json' | ConvertFrom-Json
if ($package.name -ne 'karaoke-studio') { throw 'package name incorreto' }
if (-not $angular.projects.'karaoke-studio') { throw 'Angular project incorreto' }
if (Test-Path -LiteralPath 'frontend') { throw 'pasta frontend residual' }
if (Test-Path -LiteralPath 'backend') { throw 'pasta backend residual' }
```

Expected: no exception.

- [ ] **Step 5: Fazer revisão manual proporcional ao risco**

Run `npm start`, open `http://localhost:4200`, then verify:

- page renders without console errors;
- local selector and YouTube tab render;
- pitch controls remain disabled before local DSP is ready;
- responsive page has no horizontal overflow at approximately 375 px and 1200 px.

Do not claim audible pitch quality or live Netlify deployment was verified unless actual media/domain testing was performed.

- [ ] **Step 6: Entregar o resultado ao usuário**

Report:

- Node, Angular and SoundTouch versions;
- final test count and build output path;
- deleted Java/Maven paths;
- Netlify build command and publish directory;
- manual checks actually performed;
- remaining user action: place the directory in a Git repository and connect that repository to Netlify.
