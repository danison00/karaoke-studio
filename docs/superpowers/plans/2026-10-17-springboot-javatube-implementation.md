# Spring Boot estático + JavaTube Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Empacotar o Angular e uma API Spring Boot no mesmo JAR, resolvendo streams de vídeos públicos do YouTube com JavaTube e mantendo o ajuste de tom no navegador.

**Architecture:** O Maven executará o build Angular e copiará `dist/karaoke-studio/browser` para `BOOT-INF/classes/static`. O Spring MVC servirá a SPA e dois endpoints same-origin (`metadata` e `media`); um adapter isola JavaTube, seleciona primeiro um stream progressivo e usa áudio-only como fallback. O Angular deixará de usar o IFrame API como player principal e conectará o elemento de mídia local/YouTube ao `AudioEngineService`.

**Tech Stack:** Java 26, Spring Boot 4.1.1, Maven, Spring MVC `StreamingResponseBody`, JavaTube fixado por commit via JitPack ou módulo interno equivalente, Angular 22, Node 26.12.0/npm 11.20.0 via NVM, SoundTouch AudioWorklet e Vitest.

**Spec:** `docs/superpowers/specs/2026-10-17-springboot-static-youtube-design.md`

## Global Constraints

- A aplicação aceitará URLs públicas de `youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtu.be` e Shorts.
- A aplicação não será proxy para domínios arbitrários; URLs, redirects e destinos privados serão rejeitados.
- O endpoint não persistirá vídeos nem exporá URLs temporárias assinadas do YouTube.
- O stream progressivo será preferido; o fallback será áudio-only usando o mesmo elemento HTMLMediaElement.
- O build local usará Node `26.12.0` com `nvm use 26.12.0`; NVM não controla Java.
- O backend será Spring MVC com executor limitado, timeouts e suporte a Range quando o upstream permitir.
- A versão/commit do JavaTube será imutável; `master-SNAPSHOT` não será usado.
- Vídeos privados, DRM, autenticação e limitações impostas pelo YouTube não serão contornados.

## Review Focus

- JavaTube pode entregar stream direto, SABR ou OTF: o POC deve provar leitura incremental e registrar o fallback permitido.
- Um vídeo pode não ter stream progressivo: metadata, UI e reprodução precisam assumir `audio-only` sem quebrar o pitch.
- O navegador pode fazer Range, seek ou cancelar a conexão: o proxy precisa propagar/fechar corretamente.
- Um destino resolvido pode redirecionar ou resolver para rede privada: a validação de destino deve impedir SSRF.
- O build deve conter a SPA e o AudioWorklet no JAR, inclusive em uma instalação limpa sem `node_modules`.

---

### Task 1: Preparar o projeto Maven e o contrato de build

**Files:**
- Create: `pom.xml`
- Create: `.java-version`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/karaokestudio/KaraokeStudioApplication.java`
- Create: `src/main/java/com/karaokestudio/web/SpaForwardController.java`
- Test: `src/test/java/com/karaokestudio/StaticApplicationSmokeTest.java`
- Modify: `.gitignore`, `README.md`

**Interfaces:**
- Produces: aplicação Spring Boot executável em `target/karaoke-studio-*.jar` e assets Angular em `classpath:/static`.
- Consumes: `package.json`, `package-lock.json` e saída `dist/karaoke-studio/browser`.

- [ ] **Step 1: Escrever o teste de smoke do runtime**

  Criar um teste Spring Boot que valide `GET /` com `200`, `Content-Type` HTML e `GET /audio-worklets/soundtouch-processor.js` com `200` após os assets de teste serem empacotados.

- [ ] **Step 2: Rodar o teste para confirmar a falha**

  Rodar `mvn -q -Dtest=StaticApplicationSmokeTest test`. Esperado: falha por ausência de `pom.xml`/aplicação Spring e de recursos estáticos.

- [ ] **Step 3: Criar o `pom.xml` e o bootstrap Spring**

  Configurar parent Spring Boot 4.1.1, Java release 26, `spring-boot-starter-web`, `spring-boot-starter-test`, plugin `frontend-maven-plugin` para Node `v26.12.0`/npm `11.20.0`, execução de `npm ci` e `npm run build` antes de `process-resources`, cópia de `dist/karaoke-studio/browser` para `${project.build.outputDirectory}/static` e `spring-boot-maven-plugin` para o JAR executável. Fixar `.java-version` em `26` e registrar a configuração de porta e timeouts em `application.yml`.

- [ ] **Step 4: Implementar fallback da SPA**

  Fazer `SpaForwardController` retornar `classpath:/static/index.html` para rotas sem extensão e que não comecem por `/api/`, preservando arquivos reais como JS, CSS, imagens e worklets.

- [ ] **Step 5: Rodar o teste de smoke e o build Angular/Maven**

  Rodar `npx ng test --watch=false`, `npx ng build` e `mvn clean package`. Esperado: suíte Angular verde, JAR gerado e conteúdo estático dentro do artefato.

### Task 2: Fixar e validar a integração JavaTube

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/karaokestudio/youtube/JavaTubeResolver.java`
- Create: `src/main/java/com/karaokestudio/youtube/ResolvedYoutubeMedia.java`
- Create: `src/test/java/com/karaokestudio/youtube/JavaTubeResolverPocTest.java`
- Create or modify: `docs/superpowers/notes/javatube-poc.md`

**Interfaces:**
- Produces: `JavaTubeResolver.resolve(URI): ResolvedYoutubeMedia` com `videoId`, `title`, `mode`, `contentType`, `durationMs`, `contentLength`, `upstreamUri` e `stream`.
- Consumes: APIs públicas `Youtube`, `StreamQuery` e `Stream` do JavaTube.

- [ ] **Step 1: Escrever o teste/POC RED com dependência fixada**

  Adicionar o repositório JitPack e o artefato JavaTube usando o commit obtido do branch `master` no momento da implementação. Testar a instanciação via fake/resolver seam e documentar o POC real separado para uma URL pública controlada; o teste unitário não dependerá da rede.

- [ ] **Step 2: Rodar o POC/compilação para identificar a API real**

  Rodar `mvn -q -Dtest=JavaTubeResolverPocTest test` e, em ambiente com rede, o comando de POC documentado. Esperado: antes do adapter, falha por classe ausente ou método ainda não implementado; o POC deverá registrar se `getDefaultResolution()` e `getOnlyAudio()` expõem URL/MIME/tamanho e se o stream é SABR/OTF.

- [ ] **Step 3: Implementar o adapter JavaTube**

  Criar uma seam injetável para construção de `Youtube`, selecionar `getDefaultResolution()` quando o stream progressivo for MP4 e não SABR/OTF, senão selecionar `getOnlyAudio()`; mapear título, MIME, duração, tamanho e flags. O adapter nunca gravará arquivo e converterá exceções da biblioteca em uma exceção interna estável.

- [ ] **Step 4: Rodar testes unitários do resolver**

  Rodar `mvn -q -Dtest=JavaTubeResolverPocTest test`. Esperado: PASS sem acesso à rede para os casos de seleção progressiva, fallback audio-only, stream inexistente e erro de resolução.

### Task 3: Validar URLs, destino upstream e limites operacionais

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/YoutubeUrlValidator.java`
- Create: `src/main/java/com/karaokestudio/youtube/YoutubeUrl.java`
- Create: `src/main/java/com/karaokestudio/youtube/UpstreamHostPolicy.java`
- Create: `src/main/java/com/karaokestudio/web/RequestLimiter.java`
- Test: `src/test/java/com/karaokestudio/youtube/YoutubeUrlValidatorTest.java`
- Test: `src/test/java/com/karaokestudio/youtube/UpstreamHostPolicyTest.java`
- Test: `src/test/java/com/karaokestudio/web/RequestLimiterTest.java`

**Interfaces:**
- Produces: `YoutubeUrlValidator.validate(String): YoutubeUrl`, `UpstreamHostPolicy.assertAllowed(URI): void` e `RequestLimiter.acquire(String): AutoCloseable`.
- Consumes: `app.youtube.max-url-length`, hosts permitidos e limites configurados em `application.yml`.

- [ ] **Step 1: Escrever testes RED de validação e limites**

  Cobrir watch, `youtu.be`, Shorts, query ausente, host parecido, HTTP, URL acima de 2.048 caracteres, loopback/link-local, redirect para domínio não permitido, 10 resoluções por minuto e terceiro stream concorrente do mesmo IP.

- [ ] **Step 2: Rodar os testes e confirmar falha**

  Rodar `mvn -q -Dtest=YoutubeUrlValidatorTest,UpstreamHostPolicyTest,RequestLimiterTest test`. Esperado: falha por classes ausentes.

- [ ] **Step 3: Implementar validação, SSRF policy e limiter**

  Normalizar somente URLs HTTPS e hosts explícitos, extrair `videoId` de 11 caracteres, bloquear credenciais/portas não padrão e validar IP resolvido contra loopback, link-local, RFC1918 e IPv6 local. Implementar limiter configurável por IP com janela de 1 minuto, máximo 2 streams simultâneos por IP e máximo global 8.

- [ ] **Step 4: Rodar os testes e confirmar PASS**

  Rodar o mesmo comando. Esperado: todos os casos de host, SSRF e concorrência PASS.

### Task 4: Implementar metadata, seleção e proxy HTTP de mídia

**Files:**
- Create: `src/main/java/com/karaokestudio/youtube/YoutubeMediaService.java`
- Create: `src/main/java/com/karaokestudio/youtube/UpstreamStreamClient.java`
- Create: `src/main/java/com/karaokestudio/web/YoutubeMediaController.java`
- Create: `src/main/java/com/karaokestudio/web/ApiExceptionHandler.java`
- Create: `src/main/java/com/karaokestudio/web/ApiError.java`
- Test: `src/test/java/com/karaokestudio/web/YoutubeMediaControllerTest.java`
- Test: `src/test/java/com/karaokestudio/youtube/UpstreamStreamClientTest.java`

**Interfaces:**
- Produces: `GET /api/youtube/metadata?url=...` e `GET /api/youtube/media?url=...`.
- Consumes: `YoutubeUrlValidator`, `RequestLimiter`, `JavaTubeResolver` e `UpstreamHostPolicy`.

- [ ] **Step 1: Escrever testes RED do contrato HTTP**

  Cobrir metadata `200` sem URL assinada no JSON, URL inválida `400`, vídeo não encontrado `404`, falha upstream `502`, media progressive `200` com `Content-Type`, `Content-Length`, `Accept-Ranges` e `X-Karaoke-Media-Mode`, Range válido `206` com `Content-Range`, Range inválido `416`, erro de quota `429` e rota não-API preservada para SPA.

- [ ] **Step 2: Rodar os testes e confirmar falha**

  Rodar `mvn -q -Dtest=YoutubeMediaControllerTest,UpstreamStreamClientTest test`. Esperado: falha por controllers/client inexistentes.

- [ ] **Step 3: Implementar o serviço e o cliente upstream**

  Usar `HttpClient`/cliente bloqueante com connect/read timeout, `StreamingResponseBody`, propagação de Range e fechamento de `InputStream` no cancelamento. Para stream direto, enviar bytes sob demanda sem buffer completo. Para SABR/OTF, usar somente a rotina incremental comprovada no POC e rejeitar formatos que não possam ser entregues como mídia browser-playable.

- [ ] **Step 4: Implementar controllers e mapeamento de erros**

  Fazer metadata retornar título, vídeo, modo, MIME e duração sem `upstreamUri`; fazer media resolver novamente, aplicar policy/limiter, definir headers e mapear falhas antes do primeiro byte. Depois do primeiro byte, fechar a conexão e deixar o Angular tratar `MEDIA_ERR_NETWORK` como stream interrompido.

- [ ] **Step 5: Rodar os testes do contrato**

  Rodar o mesmo comando. Esperado: PASS em metadata, headers, Range, erros e ausência de vazamento da URL assinada.

### Task 5: Migrar o Angular para mídia same-origin e pitch YouTube

**Files:**
- Create: `src/app/core/services/youtube-media.service.ts`
- Modify: `src/app/core/services/youtube-player.service.ts`, `src/app/shared/components/media-player/media-player.ts`, `src/app/shared/components/media-player/media-player.html`, `src/app/shared/components/media-player/media-player.scss`, `src/app/features/karaoke/karaoke-page.component.ts`, `src/app/core/services/karaoke-store.service.ts`, `src/app/shared/components/media-source-selector/media-source-selector.html`, `src/app/shared/components/status-bar/status-bar.html`
- Test: `src/app/core/services/youtube-media.service.spec.ts`, `src/app/shared/components/media-player/media-player.spec.ts`, `src/app/features/karaoke/karaoke-page.component.spec.ts`, `src/app/core/services/karaoke-store.service.spec.ts`

**Interfaces:**
- Produces: `YoutubeMediaService.resolve(url): Promise<YoutubeMediaMetadata>` e `YoutubeMediaService.mediaUrl(url): string`.
- Consumes: endpoint `/api/youtube/metadata`, `/api/youtube/media`, `MediaPlayerService.loadLocal`/novo `loadElement` e `AudioEngineService.attach`.

- [ ] **Step 1: Escrever testes RED da migração Angular**

  Cobrir URL codificada same-origin, metadata progressive/audio-only, criação de `HTMLVideoElement` sem iframe, `canProcessPitch: true` após attach, troca de source, play/pause/seek e atualização da UI para não mostrar “YouTube oficial”/“tom não disponível”.

- [ ] **Step 2: Rodar os testes Angular e confirmar falha**

  Rodar `npx ng test --watch=false`. Esperado: falhas nos testes novos e nos testes antigos que ainda esperam IFrame API ou YouTube sem pitch.

- [ ] **Step 3: Implementar serviço e elemento de mídia same-origin**

  Adaptar `MediaPlayer`/`MediaPlayerService` para carregar a URL da API no elemento `<video>`, fazer `AudioEngineService.attach(element)`, ouvir eventos nativos e esconder o vídeo no modo audio-only sem trocar a fonte de áudio.

- [ ] **Step 4: Remover o bloqueio de pitch para YouTube**

  Remover a condição `sourceType === 'youtube'` de `pitchDisabled`, atualizar `KaraokeStore.statusMessage` e estado para reportar “Processando áudio YouTube” quando pitch estiver ativo, e preservar reset/volume/seek.

- [ ] **Step 5: Rodar a suíte Angular**

  Rodar `npx ng test --watch=false` e `npx ng build`. Esperado: todos os testes PASS e build Angular concluído.

### Task 6: Documentar execução, remover contrato Netlify e integrar verificação final

**Files:**
- Modify: `README.md`, `docs/arquitetura-e-limitacoes.md`, `docs/roteiro-teste-manual.md`, `package.json`, `.nvmrc`
- Delete: `netlify.toml` somente após confirmar que não há mais fluxo Netlify ativo
- Create: `Dockerfile` opcional apenas se o host escolhido exigir container
- Test: `src/test/java/com/karaokestudio/PackagedApplicationTest.java`

**Interfaces:**
- Consumes: endpoints, build Maven, scripts npm e limites definidos nas tarefas anteriores.
- Produces: documentação para `nvm use 26.12.0`, `mvn clean package`, `java -jar` e teste manual de vídeo/pitch.

- [ ] **Step 1: Escrever teste RED de empacotamento**

  Validar após `mvn clean package` que o JAR contém `BOOT-INF/classes/static/index.html`, bundle Angular e `audio-worklets/soundtouch-processor.js`, além das classes do backend.

- [ ] **Step 2: Implementar documentação e limpar referências Netlify/IFrame**

  Atualizar README e docs para o JAR Spring Boot, explicar que `nvm` controla Node, registrar Java 26, documentar `metadata/media`, fallback audio-only, restrições de vídeos e aviso de autorização. Remover `netlify.toml` se o novo contrato substituir definitivamente o deploy estático.

- [ ] **Step 3: Rodar verificação final completa**

  Executar `npx ng test --watch=false`, `npx ng build`, `mvn clean package`, inspecionar o JAR e iniciar `java -jar` em porta local para testar `/`, uma rota SPA, `/api/youtube/metadata` com URL inválida e `/api/youtube/media` com URL inválida. Esperado: zero falhas, assets no JAR e erros HTTP estáveis.

- [ ] **Step 4: Revisar o diff e registrar limitações**

  Confirmar que nenhum segredo, URL assinada, arquivo de vídeo ou `node_modules` entrou no artefato versionado; registrar no README qualquer limitação descoberta no POC JavaTube.
