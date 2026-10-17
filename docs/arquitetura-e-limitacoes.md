# Arquitetura e limitações

## Visão geral

O Maven fixa Node 26.12.0/npm 11.20.0, executa `npm ci` e `npm run build`, copia `dist/karaoke-studio/browser` para `BOOT-INF/classes/static` e gera um JAR executável Spring Boot. A SPA e a API usam a mesma origem; não há CORS entre duas aplicações.

```text
Spring Boot JAR
  ├── / e assets → Angular SPA
  ├── /api/youtube/preparations → sessão + validação + JavaTube
  ├── /api/youtube/.../hls → playlist EVENT/fMP4 e segmentos publicados
  └── /api/youtube/{id}/media → proxy progressivo com Range
                                      ↓
       HTMLMediaElement → SoundTouch AudioWorklet → GainNode → saída
```

Arquivos locais são processados somente na máquina do usuário e não são enviados. Para YouTube, o servidor resolve e encaminha a mídia pública; o browser recebe a mídia pela mesma origem da SPA.

## Fluxo local

```text
HTMLMediaElement
  → MediaElementAudioSourceNode
  → SoundTouch AudioWorklet (pitchSemitones)
  → GainNode (volume/mute)
  → AudioContext.destination
```

O mesmo elemento `<video>` mantém o relógio e a imagem. Cada elemento recebe no máximo um `MediaElementAudioSourceNode`; object URLs, listeners e conexões são liberados ao trocar a mídia. Mudanças de tom usam uma rampa curta de 40 ms para reduzir cliques. `playbackRate` e `defaultPlaybackRate` permanecem em `1`.

O SoundTouch AudioWorklet 2.1.1 oferece processamento estéreo em thread de áudio, parâmetros separados de pitch e velocidade e licença MPL-2.0. O processor publicado pelo projeto fica disponível em `/audio-worklets/soundtouch-processor.js`.

## YouTube e JavaTube

`HlsPlaybackSessionService` valida a URL, aplica o limiter, resolve o vídeo com a versão fixa do JavaTube e escolhe primeiro um stream progressivo `video/mp4` direto. Quando só há streams adaptativos, seleciona vídeo e áudio até 360p, cria uma geração HLS EVENT com segmentos fMP4 de aproximadamente 2 segundos e publica os arquivos enquanto o FFmpeg ainda está executando.

O browser chama `POST /api/youtube/preparations`, consulta o UUID até `STREAMING` e conecta o `hls.js` ao mesmo `HTMLVideoElement`. O cliente mantém o offset lógico do intervalo atual. Um seek dentro de um range usa a playlist já publicada; um seek futuro envia `POST .../seek`, cancela o produtor anterior e inicia outra geração com `-ss`. `requestSequence` garante que o último gesto do usuário vence requisições HTTP que chegarem fora de ordem.

Cada sessão possui `playbackGeneration` e `producerGeneration` separados. Assim, um intervalo antigo continua tocando enquanto outra geração é produzida. Se a nova geração falhar, o estado fica `DEGRADED` e os ranges anteriores continuam disponíveis para recuperação.

O FFmpeg nunca recebe a URL assinada do YouTube. O `YoutubeRelayRegistry` entrega somente URLs HTTP loopback com token aleatório por sessão/geração; o controller aceita apenas clientes loopback, valida o token, encaminha Range pelo cliente HTTP com política de host/redirect e fecha as respostas ativas no cancelamento. O navegador nunca recebe esses tokens.

O proxy não grava o vídeo e não expõe `upstreamUri` no JSON. O cliente HTTP não segue redirects cegamente: cada destino precisa ser HTTPS, host permitido (`googlevideo.com`/YouTube), porta padrão e resolver somente para endereços públicos. O header `Range` é validado e encaminhado quando possível.

O JavaTube está fixado no build imutável `master-f249f5fd61-1` via JitPack. O resolver usa o cliente `WEB` com PoToken habilitado (`Youtube(url, "WEB", true, false)`), em vez do cliente padrão `VISION_OS`, para atender ao fluxo atual do YouTube. O `nodejs_wrapper` baixa e mantém o runtime Node necessário para gerar o token em `user.home/.nodejswrapper`; a imagem Docker executa com um usuário cujo diretório home é gravável. A seam e os casos de seleção ficam documentados em [javatube-poc.md](superpowers/notes/javatube-poc.md). A aplicação não contorna autenticação, DRM, restrição de idade, vídeos privados ou bloqueios impostos pelo YouTube.

## Limitações reais

- Codecs dependem do navegador e do sistema operacional, mesmo quando a extensão é aceita.
- O AudioContext pode exigir um clique antes de sair do estado suspenso.
- Pitch extremo pode produzir artefatos; a qualidade varia conforme voz, mixagem e hardware.
- O AudioWorklet adiciona pequena latência de processamento. A imagem segue o relógio do elemento; não há calibração manual de offset nesta versão.
- A validação automatizada confirma o grafo e os parâmetros, mas a qualidade sonora e a sincronização devem ser verificadas com caixas ou fones reais.
- A resolução depende da disponibilidade pública do vídeo, da rede, dos clientes/tokens aceitos pelo YouTube e do comportamento da versão fixada do JavaTube.
- Alguns formatos SABR/OTF sem URL de mídia direta podem ser recusados; não são tratados como uma URL progressiva falsa.
- O HLS exige FFmpeg 7+; streams compatíveis usam COPY e os demais usam H.264 (`libx264`) e AAC com saída 360p. FFmpeg não é incluído no JAR; o operador deve instalá-lo no host ou definir `FFMPEG_PATH`.
- O cache é temporário, limitado e removido por TTL/orçamento. Arquivos `.part` abandonados são removidos no startup; não existe biblioteca permanente do usuário.
- A primeira reprodução de um vídeo adaptativo começa depois dos primeiros arquivos HLS completos, normalmente em 5–10 segundos; a aplicação não aguarda download/muxação completos.
- O alvo inicial é Chrome em celulares, aproximadamente 360p. Safari/iOS, múltiplas qualidades, CDN, escala horizontal e persistência durável não fazem parte deste fluxo.
- Uso, cópia e redistribuição de vídeos públicos continuam sujeitos aos termos do YouTube e aos direitos aplicáveis.
