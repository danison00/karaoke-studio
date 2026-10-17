# YouTube com HLS progressivo e seek sob demanda — Design

**Status:** revisado e aguardando aprovação do usuário  
**Público deste documento:** agente implementador (Luna)  
**Data:** 2026-10-17

## Intenção

Substituir, para vídeos adaptativos do YouTube, o fluxo sequencial atual — baixar vídeo completo, baixar áudio completo, executar FFmpeg e só então liberar um MP4 — por um fluxo progressivo. O usuário deve conseguir iniciar a reprodução em aproximadamente 5 a 10 segundos no Chrome de celulares, enquanto o restante do vídeo continua sendo obtido e processado pelo servidor.

Quando o usuário avançar para um ponto ainda não gerado, a aplicação deve interromper somente o processamento daquela sessão, iniciar uma nova geração perto do ponto solicitado e retomar a reprodução assim que houver buffer suficiente. Voltar para um trecho já gerado deve reutilizar o cache sem executar FFmpeg novamente.

O Angular continua sendo compilado e servido como arquivos estáticos pelo mesmo Spring Boot. A SPA, a API, as playlists e os segmentos permanecem na mesma origem.

## Decisões do usuário

- O primeiro navegador alvo é o Chrome em celulares.
- A qualidade de vídeo alvo é 360p.
- A reprodução deve começar, em condições normais, entre 5 e 10 segundos após o link ser informado.
- O uso normal será sequencial; o usuário geralmente não avançará o vídeo.
- Se o usuário avançar para um trecho ainda indisponível, é aceitável mostrar carregamento enquanto o servidor reinicia o stream naquele ponto.
- O seek deve interromper o processamento corrente da sessão e começar a produzir mídia a partir do novo ponto.
- Áudio e vídeo devem ser consumidos em paralelo; o frontend não pode depender do download completo dos dois arquivos.
- O ajuste de tom existente, em passos de meio semitom, deve continuar funcionando no mesmo elemento `<video>`.

## Chamadas de arquitetura feitas neste design

Estas decisões não foram impostas pelo usuário; foram escolhidas para tornar a implementação segura e previsível:

- Usar HLS do tipo EVENT com segmentos fMP4/CMAF, em vez de uma única resposta HTTP fragmentada.
- Gerar segmentos com alvo de 2 segundos e normalmente iniciar após dois segmentos completos. Se aos 8 segundos houver somente um segmento completo e válido, liberar a reprodução com esse segmento para priorizar a meta de startup. Stream copy pode produzir segmentos maiores por causa dos keyframes da origem.
- Usar `hls.js`, instalado como dependência npm e empacotado no build Angular; não carregar a biblioteca por CDN.
- Manter uma sessão de reprodução por `POST`, em vez de compartilhar um processo FFmpeg mutável entre usuários. Isso impede que o seek de uma pessoa interrompa outra.
- Reutilizar segmentos entre seeks da mesma sessão. Compartilhamento de segmentos entre sessões diferentes fica fora deste incremento.
- Alimentar o FFmpeg por dois relays HTTP internos do Spring, um para vídeo e outro para áudio. Os relays preservam a validação de redirects/hosts já existente e evitam entregar URLs assinadas do YouTube diretamente ao processo externo.
- Usar stream copy no fluxo inicial quando os codecs forem H.264/AVC e AAC. Uma geração iniciada por seek pode transcodificar em 360p para obter início temporal preciso e keyframes regulares.
- Aplicar debounce de 400 ms ao gesto de seek; somente a última posição escolhida gera uma chamada ao backend.
- Limitar o MVP a uma instância do Spring Boot. Escala horizontal e armazenamento compartilhado não fazem parte desta entrega.

## Objetivos

- Remover a espera pelo download e pela muxação completos antes da primeira reprodução.
- Produzir áudio e vídeo sincronizados em segmentos reproduzíveis à medida que chegam.
- Preservar a reprodução progressiva direta já existente como caminho rápido para streams que já contêm áudio e vídeo.
- Manter no frontend a duração e a posição relativas ao vídeo original, mesmo quando uma playlist HLS começa no meio do vídeo.
- Permitir seek imediato em qualquer trecho já presente no cache da sessão.
- Reiniciar rapidamente a geração ao avançar para um trecho ainda não produzido.
- Cancelar processos, requisições e arquivos obsoletos sem permitir que uma geração antiga volte a publicar estado.
- Manter URLs assinadas, tokens internos, caminhos locais e diagnósticos do FFmpeg fora da API pública.
- Continuar servindo Angular e API no mesmo JAR e na mesma origem.

## Não objetivos

- Compatibilidade inicial com Safari/iOS, Firefox móvel, smart TVs ou navegadores sem Media Source Extensions.
- Low-Latency HLS com partial segments (`EXT-X-PART`). Segmentos fMP4 completos são suficientes para a meta de 5–10 segundos.
- Qualidades adaptativas múltiplas ou seleção manual de resolução.
- Resolução maior que 360p neste incremento.
- Manter processamento ativo indefinidamente sem cliente consumindo a sessão.
- Compartilhar uma geração FFmpeg ativa entre usuários diferentes.
- Escala horizontal, sticky sessions ou cache em S3/Redis.
- Biblioteca permanente de vídeos, download pelo usuário ou armazenamento durável.
- Contornar vídeos privados, pagos, autenticados, com DRM, restrição etária/regional ou formatos que JavaTube não consiga resolver de forma pública.

## Antiobjetivos

A implementação será considerada incorreta mesmo que reproduza vídeo se:

- ainda esperar o arquivo completo para liberar a primeira imagem;
- um seek de uma sessão cancelar ou alterar a reprodução de outra sessão;
- o frontend criar elementos de mídia diferentes para imagem e áudio;
- o pitch alterar `playbackRate` ou dessincronizar áudio e vídeo;
- segmentos incompletos puderem ser servidos;
- o FFmpeg puder receber uma URL arbitrária enviada pelo usuário;
- processos FFmpeg abandonados permanecerem executando após troca de vídeo, novo seek ou expiração da sessão.

## Contexto atual

O fluxo adaptativo atual está concentrado em `YoutubePreparationService`: baixa primeiro o vídeo para `.video.part`, depois o áudio para `.audio.part`, chama `FfmpegRunner.mux(...)`, publica um MP4 e somente então muda o estado para `READY`. Esse desenho explica a espera observada de aproximadamente dois minutos.

O frontend chama `YoutubeMediaService.prepare(...)`, consulta o status `PREPARING` e só atribui `mediaUrl` ao elemento de vídeo quando recebe `READY`. `MediaPlayerService` já mantém um único `HTMLVideoElement` ligado ao `AudioEngineService`, o que deve ser preservado.

## Arquitetura proposta

```text
Angular / hls.js / <video>
          |
          | POST sessão, GET estado, POST seek, GET HLS
          v
Spring Boot
  ├─ PlaybackSessionService
  │    ├─ estado, geração reproduzida e geração produtora
  │    ├─ intervalos já gerados
  │    └─ cancelamento/expiração
  ├─ JavaTubeResolver
  │    └─ vídeo H.264 <= 360p + áudio AAC
  ├─ relay interno de vídeo ──> cliente HTTP validado ──> Google Video
  ├─ relay interno de áudio ──> cliente HTTP validado ──> Google Video
  └─ FFmpeg
       └─ init.mp4 + playlist EVENT + segment-*.m4s
                    |
                    └─ publicados atomicamente no cache da sessão
```

### Dois caminhos de mídia

1. **PROGRESSIVE**: quando JavaTube fornece um stream MP4 com áudio e vídeo, manter o proxy atual com suporte a `Range`. O navegador faz seek normalmente e não existe FFmpeg/HLS.
2. **HLS**: quando áudio e vídeo são adaptativos, o FFmpeg lê os dois relays simultaneamente e grava uma playlist incremental com segmentos fMP4. O frontend começa quando a geração é marcada como reproduzível.

O modo público `MUXED` deixa de representar o novo fluxo e passa a ser `HLS`. Compatibilidade temporária com registros antigos em memória não é necessária, pois as sessões não sobrevivem ao restart do servidor.

## Sessão, geração e intervalo

Uma **sessão** representa a reprodução de um cliente e possui UUID próprio. Cada chamada a `POST /api/youtube/preparations` cria uma nova sessão, mesmo para o mesmo `videoId`.

Uma **geração** representa uma execução do FFmpeg dentro da sessão. A primeira usa geração `0`. Cada seek que exige novo conteúdo incrementa o número da geração. O diretório da geração é imutável depois que ela é cancelada ou concluída.

A sessão distingue dois papéis:

- `playbackGeneration`: geração cuja playlist está selecionada para o usuário;
- `producerGeneration`: geração cujo FFmpeg está ativo e acrescentando novos segmentos, podendo ser diferente da geração reproduzida.

Essa separação permite reproduzir imediatamente um trecho antigo em cache enquanto uma nova geração prepara a continuação daquele trecho. Só pode existir uma `producerGeneration` por sessão.

Um **intervalo disponível** descreve qual parte lógica do vídeo original uma geração consegue reproduzir:

```json
{
  "generation": 2,
  "fromMs": 118000,
  "toMs": 146400,
  "playlistUrl": "/api/youtube/preparations/{id}/hls/2/index.m3u8"
}
```

Os intervalos anteriores permanecem no cache até a sessão expirar ou o orçamento de disco exigir remoção. Somente a `producerGeneration` pode continuar acrescentando segmentos.

## Estados da sessão

- `RESOLVING`: JavaTube está resolvendo metadados e streams.
- `BUFFERING`: FFmpeg foi iniciado, mas ainda não existem segmentos suficientes.
- `STREAMING`: playlist, init segment e buffer inicial já podem ser reproduzidos; novos segmentos continuam sendo acrescentados.
- `READY`: existe cobertura reproduzível até o fim lógico do vídeo e não há produtora pendente.
- `DEGRADED`: a produção mais recente falhou, mas um ou mais intervalos armazenados continuam reproduzíveis e novos seeks são aceitos.
- `FAILED`: a sessão não pode continuar e contém erro sanitizado.
- `CANCELLED`: a sessão foi encerrada pelo cliente ou pela limpeza de inatividade.

O status descreve a sessão. `FAILED` só é usado quando não existe intervalo reproduzível; uma falha de produção com conteúdo antigo preservado resulta em `DEGRADED`. Gerações antigas canceladas continuam listadas em `availableRanges` enquanto seus arquivos existirem.

## Fluxo inicial

1. O Angular envia o link a `POST /api/youtube/preparations`.
2. O servidor valida e extrai o `videoId`, cria imediatamente a sessão em `RESOLVING` e devolve `202`. A resolução JavaTube ocorre no executor, não bloqueando a resposta inicial.
3. O resolver seleciona no máximo 360p, preferindo MP4/H.264 para vídeo e MP4/AAC para áudio. Streams SABR/OTF ou sem URL direta continuam recusados.
4. Se a mídia for progressiva, a sessão muda diretamente para `READY` com o `mediaUrl` atual.
5. Se for adaptativa, são criados dois tokens internos aleatórios e de uso restrito. O FFmpeg abre os relays de vídeo e áudio em paralelo.
6. O FFmpeg grava `init.mp4`, `index.m3u8` e `segment-000000.m4s`, `segment-000001.m4s`, etc. Arquivos são publicados somente depois de completos.
7. Um monitor lê a playlist publicada e atualiza `toMs`. Normalmente, quando existirem o init segment e pelo menos dois segmentos completos, a sessão muda para `STREAMING` e passa a expor `playlistUrl`. Se o relógio de startup atingir 8 segundos e já houver um segmento completo e válido, a sessão pode mudar para `STREAMING` com um segmento. Não reiniciar em modo transcode somente por atraso de keyframe, pois o restart aumentaria a latência.
8. O Angular anexa `hls.js` ao mesmo `<video>`, carrega a playlist e libera o botão de reprodução. O FFmpeg continua até o fim, cancelamento, timeout, erro ou inatividade.
9. Ao terminar normalmente, a playlist recebe `EXT-X-ENDLIST` e a sessão muda para `READY`.

## Seek sob demanda

### Trecho disponível

Ao receber um seek, o frontend primeiro consulta os intervalos conhecidos:

- se o alvo estiver na geração atualmente carregada, ajustar `video.currentTime` usando o offset lógico;
- se estiver em outra geração armazenada, enviar o seek para selecionar essa geração, carregar sua playlist e posicionar o elemento no deslocamento correspondente;
- ao selecionar uma geração antiga, cancelar a `producerGeneration` que não seja uma continuação contígua desse intervalo e iniciar uma nova geração produtora a partir do final disponível do intervalo selecionado;
- a reprodução começa imediatamente no cache; a nova produtora prepara a continuação em paralelo e não bloqueia o seek.

Quando a reprodução se aproximar a 6 segundos do final de um intervalo cancelado, o frontend consulta o estado. Se a continuação já estiver `STREAMING`, troca para sua playlist mantendo o tempo lógico; caso contrário, mostra buffering no limite e aguarda. O backend gera uma pequena sobreposição de 2 segundos entre intervalos para evitar lacunas. A troca pode causar uma pausa curta, mas não deve voltar ou pular o relógio lógico.

### Trecho ainda não disponível

1. Enquanto o usuário arrasta a barra, a interface atualiza apenas a prévia de tempo.
2. Após 400 ms sem mudança, ou ao terminar o gesto, o Angular envia `POST /api/youtube/preparations/{id}/seek` com `positionMs` e `requestSequence` monotonicamente crescente.
3. O servidor valida `0 <= positionMs < durationMs`, registra a maior sequência aceita, incrementa o identificador da nova geração quando necessário e marca a produtora anterior como encerrada.
4. O processo FFmpeg anterior recebe término gracioso; depois de 2 segundos, se ainda estiver vivo, recebe término forçado. Os relays e requisições HTTP anteriores são cancelados.
5. A nova geração começa no ponto solicitado. Para busca precisa, o FFmpeg usa `-ss` como opção de entrada nos dois inputs e transcodifica a geração para H.264/AAC 360p. O accurate seek descarta o material entre o keyframe anterior e o ponto solicitado.
6. A nova playlist possui linha de tempo local iniciando em zero. A resposta informa `timelineOffsetMs = positionMs`; assim, o frontend apresenta `tempoLógico = timelineOffsetMs + video.currentTime`.
7. Quando o buffer inicial estiver pronto, o Angular carrega a nova `playlistUrl`, posiciona no deslocamento retornado e retoma automaticamente somente se o vídeo estava tocando antes do seek.

Se outro seek chegar enquanto a nova geração ainda está em `BUFFERING`, ele substitui o anterior. “Último seek vence” significa o último gesto emitido pelo cliente, não a ordem de chegada HTTP. Cada tarefa captura seu número de geração e `requestSequence` e deve verificar ambos antes de alterar estado ou publicar resposta. Uma requisição com sequência menor ou igual à já aceita retorna o snapshot corrente sem executar ação; o frontend também ignora respostas que não correspondam à sua sequência mais recente.

Se a nova produtora falhar e houver intervalos antigos, a sessão muda para `DEGRADED`, mantém a `playbackGeneration` e aceita seeks nos intervalos preservados ou uma nova tentativa em outro ponto. Somente uma sessão sem qualquer conteúdo reproduzível muda para `FAILED`.

### Precisão e tolerância

- Busca já armazenada: mesma precisão do elemento HTML e do segmento HLS.
- Busca que reinicia FFmpeg: alvo de erro máximo de 250 ms.
- Se os encoders H.264/AAC necessários para busca precisa não estiverem disponíveis, retornar erro claro; não retomar silenciosamente vários segundos antes do ponto pedido.

## Contrato HTTP

### Criar sessão

`POST /api/youtube/preparations`

Corpo:

```json
{ "url": "https://www.youtube.com/watch?v=..." }
```

Resposta inicial `202 Accepted`, `Location: /api/youtube/preparations/{id}` e `Retry-After: 1`.

### Consultar sessão

`GET /api/youtube/preparations/{id}`

```json
{
  "id": "uuid",
  "videoId": "jOQsunXHQtU",
  "title": "Título",
  "status": "STREAMING",
  "mode": "HLS",
  "durationMs": 252000,
  "playbackGeneration": 1,
  "producerGeneration": 1,
  "acceptedSeekSequence": 4,
  "requestedPositionMs": 120000,
  "timelineOffsetMs": 120000,
  "playlistUrl": "/api/youtube/preparations/{id}/hls/1/index.m3u8",
  "availableRanges": [
    {
      "generation": 0,
      "fromMs": 0,
      "toMs": 23800,
      "playlistUrl": "/api/youtube/preparations/{id}/hls/0/index.m3u8"
    },
    {
      "generation": 1,
      "fromMs": 120000,
      "toMs": 132200,
      "playlistUrl": "/api/youtube/preparations/{id}/hls/1/index.m3u8"
    }
  ],
  "mediaUrl": null,
  "error": null,
  "recoverableError": null
}
```

Durante `RESOLVING`, `title`, `mode`, `playlistUrl` e duração podem ser nulos. Para `PROGRESSIVE`, `mediaUrl` é preenchido e `playlistUrl` é nulo.

### Solicitar seek

`POST /api/youtube/preparations/{id}/seek`

```json
{ "positionMs": 120000, "requestSequence": 4 }
```

- `200 OK` se o ponto já estiver em um intervalo armazenado; a resposta seleciona a geração reutilizada.
- `202 Accepted` se uma nova geração for iniciada; a resposta fica em `BUFFERING`.
- `416 Range Not Satisfiable` para posição fora da duração.
- Uma sequência obsoleta retorna `200 OK` com o snapshot inalterado e o maior `acceptedSeekSequence`.
- `409 Conflict` para sessão cancelada, progressiva ou sem metadados suficientes. Sessões `DEGRADED` continuam aceitando seek.

### Servir HLS

- `GET /api/youtube/preparations/{id}/hls/{generation}/index.m3u8`
- `GET /api/youtube/preparations/{id}/hls/{generation}/init.mp4`
- `GET /api/youtube/preparations/{id}/hls/{generation}/segment-{number}.m4s`

Tipos:

- playlist: `application/vnd.apple.mpegurl` e `Cache-Control: no-store`;
- init segment: `video/mp4`;
- fragmento: `video/iso.segment`;
- init/fragmentos concluídos: cache privado e imutável durante a vida da sessão.

O nome solicitado deve corresponder a uma allowlist estrita. Resolver caminho com `normalize()` e exigir que continue dentro do diretório da geração. Arquivos `.tmp` nunca são servidos.

### Encerrar sessão

`DELETE /api/youtube/preparations/{id}` retorna `204 No Content`, cancela geração, relays e tarefas e agenda remoção do cache. O frontend faz essa chamada de melhor esforço ao trocar de vídeo ou desmontar o player. A limpeza por inatividade continua obrigatória porque navegadores móveis podem fechar sem enviar `DELETE`.

## Relays internos de entrada

O FFmpeg não recebe a URL assinada do YouTube diretamente. Para cada geração, o backend cria URLs como:

```text
http://127.0.0.1:{porta}/api/internal/youtube-relay/{token}/video
http://127.0.0.1:{porta}/api/internal/youtube-relay/{token}/audio
```

Requisitos:

- token criptograficamente aleatório, associado a uma geração e nunca retornado ao browser;
- aceitar apenas chamadas originadas de endereço loopback;
- expirar ao cancelar ou concluir a geração;
- encaminhar `Range` validado, `Content-Length`, `Content-Range`, `Accept-Ranges` e `Content-Type`;
- validar cada redirect e cada host/IP usando `UpstreamHostPolicy` e o cliente HTTP já existente;
- limitar bytes e duração de leitura;
- cancelar o corpo upstream quando o processo for encerrado;
- não registrar token nem URL assinada.

Esse relay acrescenta somente um salto local e permite que o FFmpeg faça leitura incremental e seek por range sem enfraquecer a fronteira SSRF existente.

## FFmpeg

### Geração inicial compatível

Forma conceitual, sem shell:

```text
ffmpeg -nostdin -hide_banner -loglevel warning -y
  -i <relay-video> -i <relay-audio>
  -map 0:v:0 -map 1:a:0
  -c:v copy -c:a copy
  -f hls
  -hls_time 2
  -hls_list_size 0
  -hls_playlist_type event
  -hls_segment_type fmp4
  -hls_fmp4_init_filename init.mp4
  -hls_segment_filename segment-%06d.m4s
  -hls_flags temp_file+independent_segments
  index.m3u8
```

`hls_time` é um alvo; em stream copy o corte ocorre no próximo keyframe. O monitor não deve assumir duração exata de 2 segundos, devendo somar os valores `EXTINF` efetivamente publicados.

### Geração após seek ou codec incompatível

Usar seek de entrada nos dois relays, escala limitada a 360p preservando proporção, H.264/AAC, keyframes regulares e pixel format compatível:

```text
ffmpeg ...
  -ss <position> -i <relay-video>
  -ss <position> -i <relay-audio>
  -map 0:v:0 -map 1:a:0
  -vf scale=-2:360
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p
  -force_key_frames expr:gte(t,n_forced*2)
  -c:a aac -b:a 128k
  <mesmas opções HLS>
```

Os argumentos são uma `List<String>` para `ProcessBuilder`; nunca construir comando de shell. A implementação deve confirmar a sintaxe final em teste real, inclusive quoting da expressão de keyframe sem shell.

### Controle de processo

O executor atual retorna apenas ao fim e não permite cancelamento externo. Ele deve evoluir para devolver um handle cancelável contendo processo, conclusão e diagnósticos limitados. Encerramento deve destruir a árvore de processos quando suportado, esperar prazo curto e forçar término se necessário.

Limites sugeridos:

- duas gerações FFmpeg ativas por instância;
- fila máxima de oito sessões aguardando resolução/início;
- timeout absoluto de 15 minutos por geração;
- timeout de 2 minutos sem acesso do cliente durante `STREAMING`;
- logs de stderr limitados a 64 KiB por geração.

## Cache

Estrutura sugerida:

```text
{cache-root}/
  {preparation-id}/
    generation-0/
      index.m3u8
      init.mp4
      segment-000000.m4s
    generation-1/
      ...
```

Regras:

- todas as partes do caminho derivam de UUID/número validado, nunca de título ou URL;
- segmentos e playlists são publicados por rename atômico; usar `hls_flags temp_file`;
- arquivos de uma geração cancelada permanecem disponíveis, mas deixam de crescer;
- manter lease/contador durante respostas para não apagar arquivo em uso;
- sessão ativa, geração reproduzida e geração produtora nunca são removidas pela limpeza de orçamento;
- TTL recomendado: 2 horas após último acesso para sessão concluída ou cancelada;
- falhas sem segmentos úteis: 10 minutos;
- orçamento total inicial: 5 GB, removendo sessões menos recentemente usadas;
- no startup, remover `.tmp` e diretórios órfãos de sessões que não existem em memória.

## Frontend Angular

### Dependência e suporte

- adicionar a versão estável mais recente de `hls.js` disponível no momento da implementação e fixá-la no `package-lock.json`;
- usar import do pacote, não CDN;
- antes do fluxo HLS, verificar `Hls.isSupported()` e apresentar mensagem específica se MSE/fMP4 não estiver disponível;
- manter `playsinline`, necessário para experiência adequada no Chrome móvel.

### Integração com o player

- `YoutubeMediaService` cria, consulta, busca e encerra sessões.
- `MediaPlayerService` mantém uma instância `Hls` para a fonte YouTube atual e a destrói ao trocar fonte.
- `hls.attachMedia(element)` usa o mesmo `<video>` já conectado ao `AudioEngineService`.
- Não chamar novamente `createMediaElementSource` ao trocar geração; o elemento permanece o mesmo.
- A duração exibida vem de `durationMs` da API, não de `video.duration` da playlist parcial.
- A posição exibida é `timelineOffsetMs + video.currentTime`.
- Ao trocar de geração, ignorar temporariamente eventos de tempo da mídia antiga para não fazer a barra saltar para zero.
- O pitch continua em passos de 0,5 semitom e `playbackRate/defaultPlaybackRate` continuam em `1`.

Configuração inicial sugerida para celular:

- `maxBufferLength: 20` segundos;
- `backBufferLength: 60` segundos;
- `lowLatencyMode: false`;
- dois segmentos completos antes de marcar como reproduzível, com fallback para um segmento válido aos 8 segundos.

Esses valores são chamadas do implementador e podem ser ajustados por testes reais sem mudar o contrato.

### UX

- `RESOLVING`: “Localizando vídeo…”
- `BUFFERING` inicial: “Preparando os primeiros segundos…”
- `STREAMING`: reprodução normal; buffering do navegador usa “Carregando…”
- seek fora do cache: pausar imagem e mostrar “Carregando a partir de 02:00…”
- se estava reproduzindo antes do seek, retomar quando `MANIFEST_PARSED` e buffer inicial estiverem prontos;
- se estava pausado, permanecer pausado;
- seek repetido: somente o último alvo deve aparecer e ser executado.

## Concorrência e corrida

- Cada sessão possui lock próprio; não usar um lock global durante rede ou FFmpeg.
- Toda alteração assíncrona inclui `(sessionId, producerGeneration, requestSequence)` e é ignorada se a produtora ou a sequência não forem mais atuais.
- `playbackGeneration` e `producerGeneration` são campos independentes; trocar o que está sendo reproduzido não autoriza uma produtora antiga a publicar estado.
- Cancelar uma geração libera exatamente uma vez o slot de processo e a lease do limiter.
- `DELETE`, seek e conclusão podem competir; operações de encerramento precisam ser idempotentes.
- Requisições de segmento de geração antiga continuam válidas enquanto o arquivo existir.
- O frontend usa `AbortController` e um token local de carregamento para ignorar respostas atrasadas após troca de fonte.

## Segurança

- Validar URL do YouTube e `videoId` como hoje.
- Manter allowlist de hosts, HTTPS, porta padrão, DNS público e validação manual de redirects no relay.
- Não aceitar URL upstream, caminho ou nome de arquivo nos endpoints HLS/seek.
- Nunca retornar ou registrar URLs assinadas, tokens internos ou comando FFmpeg completo.
- Sanitizar stderr e mensagens públicas.
- Aplicar os limites existentes de URL, duração, bytes, IP, streams globais, resolução e fila.
- Adicionar headers `X-Content-Type-Options: nosniff` nos recursos HLS.
- Responsabilidade de uso e direitos sobre vídeos públicos continua documentada.

## Erros públicos

- `400 INVALID_YOUTUBE_URL`
- `404 YOUTUBE_NOT_FOUND` ou sessão expirada
- `409 SESSION_NOT_SEEKABLE` / `SESSION_TERMINATED`
- `413 MEDIA_TOO_LARGE`
- `416 SEEK_OUT_OF_RANGE`
- `422 NO_COMPATIBLE_STREAMS`
- `429 RATE_LIMITED`
- `502 UPSTREAM_FAILURE`
- `503 FFMPEG_UNAVAILABLE` ou `HLS_NOT_SUPPORTED` no cliente
- `504 STREAM_TIMEOUT`
- `500 STREAM_GENERATION_FAILED`, com mensagem sanitizada

Quando um seek falhar mas uma geração anterior ainda for reproduzível, a API deve preservar os intervalos antigos e permitir que o usuário volte; não apagar conteúdo útil por causa da falha mais recente.

## Observabilidade

Registrar em formato estruturado, sem dados secretos:

- `sessionId`, `videoId`, `playbackGeneration`, `producerGeneration`, `requestSequence` e posição solicitada;
- tempo de resolução JavaTube;
- tempo até primeiro segmento e tempo até `STREAMING`;
- bytes de vídeo/áudio lidos pelo relay;
- duração efetiva de cada segmento;
- motivo de encerramento do FFmpeg: concluído, seek, troca de fonte, inatividade, timeout ou erro;
- contagem de sessões/gerações ativas e tamanho do cache.

O indicador principal de sucesso é `time_to_playable_ms`: intervalo entre o início do `POST /api/youtube/preparations` no frontend e o evento `canplay` do mesmo `<video>` após a primeira playlist HLS ser anexada. O objetivo é p95 menor ou igual a 10 segundos em Chrome Android atual, aparelho intermediário, conexão estável de pelo menos 10 Mbps, instância do mesmo porte do ambiente de produção e corpus manual de pelo menos dez vídeos públicos H.264/AAC entre 3 e 10 minutos. A CI mede o mesmo intervalo com fixtures locais; vídeos públicos são validação manual por serem instáveis. `time_to_first_playing_ms` é registrado separadamente porque autoplay pode exigir nova interação do usuário no Chrome móvel.

## Estratégia de testes

### Backend unitário

- transições da máquina de estados;
- último gesto vence por `requestSequence` mesmo se as requisições chegarem fora de ordem;
- `playbackGeneration` antiga pode coexistir com uma `producerGeneration` de continuação sem corrida;
- seek dentro da geração já carregada não inicia FFmpeg; ao selecionar outra geração armazenada, a reprodução usa o cache imediatamente e pode iniciar somente a produtora de continuação;
- seek fora do intervalo cancela e cria geração;
- fim de intervalo armazenado troca para continuação com sobreposição ou entra em buffering até ela ficar pronta;
- falha de produtora preserva intervalos e coloca a sessão em `DEGRADED`;
- validação de posição e duração;
- parser de playlist calcula `toMs` com `EXTINF` reais;
- allowlist de nomes/caminhos HLS;
- relay valida redirects, Range, hosts e cancelamento;
- processo cancelável encerra normal e forçadamente;
- cleanup respeita leases, geração reproduzida e geração produtora.

### Backend integração

- fixture HTTP local com vídeo/áudio separados e suporte a Range;
- FFmpeg gera playlist antes de terminar a origem;
- playlist/segmentos ficam acessíveis enquanto novos segmentos surgem;
- seek em 120 s produz mídia iniciando dentro da tolerância de 250 ms;
- segmento parcial não pode ser obtido;
- `DELETE` encerra FFmpeg e relays;
- duas sessões não cancelam processos uma da outra.

Testes dependentes de FFmpeg devem detectar a ferramenta e ser marcados separadamente; a suíte unitária não pode depender de rede pública ou YouTube real.

### Angular

- polling libera player em `STREAMING`, sem esperar `READY`;
- criação/destruição correta de `Hls`;
- tempo lógico aplica `timelineOffsetMs`;
- seek em cache troca/posiciona playlist sem chamar backend para nova geração;
- seek futuro aplica debounce e envia somente o último valor;
- autoplay após seek respeita o estado anterior;
- troca de fonte aborta polling, destrói HLS e envia cancelamento de melhor esforço;
- pitch continua usando o mesmo elemento e passo de 0,5 semitom;
- mensagens dos novos estados.

### Manual em Chrome Android

- colar vídeo público adaptativo e medir primeira imagem/áudio;
- assistir continuamente por pelo menos cinco minutos;
- aplicar pitch positivo e negativo durante reprodução;
- avançar para trecho não gerado e confirmar retomada no ponto escolhido;
- voltar ao início e confirmar uso imediato do cache;
- arrastar a barra repetidamente e confirmar que somente o último seek permanece;
- bloquear/desbloquear a tela e validar recuperação;
- alternar Wi-Fi/dados e observar erro/retentativa sem processo órfão.

## Critérios de aceitação

- No benchmark definido em Observabilidade, `time_to_playable_ms` tem p95 de até 10 segundos, sem aguardar download completo.
- A API entra em `STREAMING` antes de `READY` e a playlist cresce enquanto o usuário assiste.
- Vídeo adaptativo reproduz imagem e áudio sincronizados em 360p.
- Pitch de meio semitom continua funcionando no mesmo `<video>` sem alterar velocidade.
- Seek dentro do intervalo disponível é imediato e não inicia processo.
- Seek fora do intervalo cancela apenas a geração da sessão, começa no novo ponto e retoma com erro temporal máximo de 250 ms.
- Ao voltar a um intervalo antigo, a reprodução começa pelo cache e uma produtora prepara a continuação; ao chegar ao limite, o relógio lógico não volta nem salta.
- Uma sequência rápida ou fora de ordem de seeks executa somente o maior `requestSequence`.
- Falha da produtora preserva conteúdo anterior e permite recuperação a partir de `DEGRADED`.
- Duas sessões simultâneas não interferem uma na outra.
- Segmentos incompletos, tokens internos e URLs assinadas nunca são expostos.
- Troca de vídeo, `DELETE`, timeout e inatividade encerram processo, relay e arquivos temporários.
- Testes Java, Angular e empacotamento do JAR passam.

## Implantação

O artefato continua sendo um único JAR Spring Boot contendo o build Angular. O host precisa fornecer FFmpeg 7 ou superior com H.264 (`libx264`) e AAC. Em Render, a implantação deve usar ambiente que instale FFmpeg explicitamente, preferencialmente uma imagem Docker reproduzível.

O cache permanece efêmero. Com uma única instância isso é suficiente; reiniciar ou redeployar invalida sessões em andamento. Caso haja escala futura para mais de uma instância, será necessário sticky routing por sessão ou armazenamento/coordenação compartilhados, fora deste design.

## Referências técnicas

- FFmpeg, opção de seek `-ss`: https://ffmpeg.org/ffmpeg.html
- FFmpeg, muxer HLS, fMP4, playlist EVENT e `temp_file`: https://ffmpeg.org/ffmpeg-formats.html#hls-2
- hls.js, suporte MSE, `startLoad`, `stopLoad` e configuração de buffer: https://github.com/video-dev/hls.js/blob/master/docs/API.md

