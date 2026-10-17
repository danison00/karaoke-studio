# Karaokê Studio

Aplicação Spring Boot que serve o build Angular e a API YouTube no mesmo JAR. Arquivos locais continuam sendo processados no navegador; vídeos públicos do YouTube são resolvidos pelo backend e reproduzidos em um único `HTMLMediaElement`, permitindo ajuste de tom em passos de meio semitom.

## Versões

- Java 26
- Node.js 26.12.0, selecionado por NVM
- npm 11.20.0
- Angular 22.2.3
- hls.js 1.7.3
- Spring Boot 4.1.1
- SoundTouch AudioWorklet 2.1.1 (MPL-2.0)

`.nvmrc` registra a versão de Node. `.java-version` registra a versão de Java esperada pelo projeto.

## Preparação no Windows

```powershell
nvm install 26.12.0 64
nvm use 26.12.0
java -version
npm ci
```

`nvm` controla somente Node/npm; o Java 26 precisa estar instalado e selecionado separadamente.

## Desenvolvimento

Para executar a aplicação completa, incluindo os endpoints YouTube:

```powershell
nvm use 26.12.0
mvn spring-boot:run
```

Abra `http://localhost:8080`. O comando Maven executa o build Angular e o Spring Boot serve `dist/karaoke-studio/browser` como `classpath:/static`.

Para vídeos adaptativos do YouTube, instale o FFmpeg 7+ no servidor e confirme que `ffmpeg` está no `PATH`. Alternativamente, configure `FFMPEG_PATH` com o caminho do executável. O fluxo progressivo e os arquivos locais continuam funcionando sem FFmpeg.

O backend grava o diagnóstico do YouTube/HLS em `karaoke-studio.log`, no diretório em que o processo Spring Boot foi iniciado. Para acompanhar em tempo real no PowerShell, use `Get-Content .\karaoke-studio.log -Tail 200 -Wait`. O caminho pode ser alterado com `KARAOKE_LOG_FILE`; o log registra o `sessionId`, a geração HLS, o relay, as respostas do upstream, o FFmpeg e as requisições da playlist, sem registrar as URLs assinadas do YouTube.

Para trabalhar apenas na tela Angular, use `npm start` e abra `http://localhost:4200`; o fluxo YouTube requer o backend na mesma origem ou um proxy de desenvolvimento.

## Testes e build de produção

```powershell
nvm use 26.12.0
npm run test:ci
npm run build
mvn clean package
java -jar target\karaoke-studio-0.1.0-SNAPSHOT.jar
```

O JAR executável inclui a SPA, o AudioWorklet e as classes da API. A saída Angular isolada fica em `dist/karaoke-studio/browser`.

## Deploy com Docker no Render

O repositório inclui um `Dockerfile` multiestágio. A etapa de build executa o Angular pelo Maven; a imagem final contém Java 26, o JAR Spring Boot e FFmpeg para o streaming HLS do YouTube.

### Testar a imagem localmente

Com Docker Desktop em execução:

```powershell
docker build -t karaoke-studio .
docker run --rm -p 8080:8080 karaoke-studio
```

Abra `http://localhost:8080`. Para interromper, pressione `Ctrl+C`.

### Publicar no Render

Há um `render.yaml` na raiz para configurar o serviço como Blueprint. No Render:

1. Abra **New > Blueprint** e conecte `danison00/karaoke-studio`.
2. Selecione a branch `main` e confirme o serviço `karaoke-studio`.
3. Revise o plano e clique em **Apply**. O Render usará o `Dockerfile` da raiz, o health check `/` e o deploy automático a cada push na `main`.

Também é possível criar manualmente em **New > Web Service**: conecte o repositório, escolha **Docker** como runtime, mantenha `Dockerfile Path` como `./Dockerfile`, configure o health check como `/` e crie o serviço.

O Spring Boot já usa a variável `PORT` fornecida pelo Render. Não defina uma porta fixa no painel. A URL final será HTTPS, o que também atende às exigências dos navegadores móveis para clipboard e reprodução de mídia.

Variáveis opcionais:

```text
JAVA_OPTS=-XX:MaxRAMPercentage=75.0
YOUTUBE_CACHE_DIR=/tmp/karaoke-studio-youtube
KARAOKE_LOG_FILE=/tmp/karaoke-studio.log
```

O armazenamento temporário do Render é suficiente para as sessões de teste; vídeos e segmentos são descartados quando a instância é reiniciada. Para produção com maior volume, use um plano com mais CPU/RAM, pois cada stream HLS usa FFmpeg. O limite padrão da aplicação continua sendo 360p, 8 streams globais e 2 streams por IP.

Depois do deploy, valide:

```text
https://SEU-SERVICO.onrender.com/
```

Cole um vídeo público do YouTube, aguarde o primeiro segmento HLS, teste play/pause, volume, mute e um avanço curto. Os diagnósticos ficam no log da aplicação; no Render, consulte a aba **Logs**.

## API YouTube

- `POST /api/youtube/preparations` com `{ "url": "https://..." }` cria uma sessão same-origin. Vídeos adaptativos retornam `STREAMING` assim que a playlist HLS fica reproduzível, sem aguardar o arquivo completo.
- `GET /api/youtube/preparations/{id}` expõe estado, gerações e intervalos HLS publicados.
- `POST /api/youtube/preparations/{id}/seek` com `{ "positionMs": 60000, "requestSequence": 3 }` reutiliza um intervalo cacheado ou inicia uma geração FFmpeg no ponto solicitado.
- `GET /api/youtube/preparations/{id}/hls/{generation}/index.m3u8` e os recursos `init.mp4`/`segment-*.m4s` servem o HLS same-origin. `DELETE /api/youtube/preparations/{id}` cancela produtores e libera cache.
- `GET /api/youtube/preparations/{id}/media` permanece para streams progressivos.

São aceitas URLs HTTPS de `youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtu.be`, Shorts e embeds com ID válido. O servidor não devolve a URL assinada do upstream, não persiste vídeos, não aceita hosts arbitrários e bloqueia destinos privados para reduzir SSRF. Vídeos privados, autenticados, DRM, bloqueados ou que o YouTube/JavaTube não consiga resolver ficam fora do contrato.

O limite padrão é de 8 streams globais, 2 por IP, 10 resoluções por minuto, vídeo adaptativo limitado a 360p, duração de 2 horas e cache temporário de 5 GB. Ajuste por `application.yml`, `FFMPEG_PATH` e `YOUTUBE_CACHE_DIR`.

## O que funciona

- Arquivos locais MP3, WAV, MP4 e WebM, conforme os codecs do navegador.
- Play/pause, reinício, seek, tela cheia, volume e mute.
- Pitch real de −12 a +12 semitons em passos de `0.5`, via SoundTouch AudioWorklet, mantendo `playbackRate` em `1`.
- Vídeos públicos do YouTube por preparação same-origin: stream progressivo direto ou HLS fMP4 progressivo de vídeo+áudio adaptativos com FFmpeg, mantendo pitch no mesmo grafo Web Audio.
- Estados de carregamento, buffering, reprodução, erro, Range e disponibilidade real do DSP.

Consulte [arquitetura e limitações](docs/arquitetura-e-limitacoes.md), o [roteiro de teste manual](docs/roteiro-teste-manual.md) e o [POC JavaTube](docs/superpowers/notes/javatube-poc.md).
