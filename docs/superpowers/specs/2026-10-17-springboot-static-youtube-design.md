# Karaokê Studio — Spring Boot monolítico com Angular estático e JavaTube

**Data:** 2026-10-17  
**Status:** rascunho para aprovação  
**Substitui como arquitetura alvo:** `2026-10-17-netlify-frontend-only-design.md`

## Intenção

Transformar o projeto em uma única aplicação Spring Boot empacotada em um JAR executável. O Angular continuará sendo o front-end, mas seu build será incorporado ao classpath do Spring Boot e servido pelo mesmo processo que expõe a API.

O backend deverá aceitar qualquer URL pública de vídeo do YouTube que a biblioteca consiga resolver, obter um stream de mídia com JavaTube e entregá-lo pelo mesmo domínio da aplicação. Assim, o navegador não acessa diretamente o YouTube para tocar a mídia e o áudio passa pelo pipeline Web Audio/SoundTouch existente, permitindo o ajuste de tom também para a fonte YouTube.

## Objetivos

- Entregar uma aplicação única: `java -jar karaoke-studio.jar`.
- Servir `index.html`, JavaScript, CSS, assets e o AudioWorklet Angular pelo Spring Boot.
- Manter a UI Angular existente e integrar a fonte YouTube ao mesmo elemento de mídia HTML usado pelo áudio local.
- Aceitar URLs públicas de `youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtu.be` e links de Shorts.
- Fazer o backend resolver e fazer proxy do stream; nunca expor ao browser a URL temporária assinada do YouTube.
- Remover a dependência funcional do YouTube IFrame API para a reprodução principal.
- Preservar o ajuste de tom, a reprodução, pausa, seek, volume e indicador de estado para YouTube.
- Manter Node/npm versionados para desenvolvimento com `nvm` e Java versionado separadamente para o build Spring.

## Fora do escopo

- Login, vídeos privados, membros, conteúdos protegidos por DRM ou vídeos que exijam autenticação.
- Banco de dados, contas de usuário, playlists persistentes ou histórico.
- Download permanente de arquivos no servidor.
- Conversão/transcodificação com FFmpeg na primeira versão.
- Hospedagem do JAR no Netlify. O destino passa a ser um host que execute Java, como uma VM, container ou plataforma Java.

## Decisão de reprodução

O backend preferirá um stream progressivo que contenha áudio e vídeo. Isso mantém a imagem do vídeo no componente visual atual e permite que o mesmo elemento `<video>` seja conectado ao `AudioEngineService`.

Quando o vídeo não oferecer um stream progressivo compatível, o backend usará o melhor stream de áudio disponível. Nesse fallback, a reprodução continua com ajuste de tom, mas a interface exibirá o estado de mídia somente-áudio em vez de prometer imagem de vídeo. A API informará o modo escolhido por cabeçalho e/ou metadado para a UI atualizar o placeholder.

Essa escolha é necessária porque o YouTube frequentemente separa vídeo e áudio em streams adaptativos; combinar os dois exigiria uma etapa de mux/transcodificação, que não fará parte deste primeiro incremento.

O fallback usará o mesmo `HTMLVideoElement`: navegadores modernos conseguem reproduzir um MIME de áudio nesse elemento, e isso mantém uma única ligação com o `AudioEngineService`. A UI esconderá a área visual e mostrará o estado áudio-only quando o metadado recebido indicar esse modo.

O suporte será direcionado aos dois últimos releases dos navegadores evergreen Chrome, Edge, Firefox e Safari. A seleção priorizará MP4/AAC quando disponível e aceitará WebM/Opus quando o navegador declarar suporte; não haverá conversão de codec no servidor.

## Arquitetura proposta

```text
Browser
  └── Angular servido por Spring Boot
        ├── /api/youtube/media?url=...
        │     ├── valida a URL e o host permitido
        │     ├── JavaTube resolve o vídeo e seleciona o stream
        │     └── proxy Spring retorna bytes + Range/Content-Type
        └── HTMLMediaElement same-origin
              └── AudioEngineService → Web Audio → SoundTouch AudioWorklet

Spring Boot executável
  ├── static/                 # gerado a partir de dist/karaoke-studio/browser
  ├── API REST
  └── integração JavaTube
```

Não haverá CORS entre front-end e API porque ambos serão servidos pela mesma origem. A comunicação do backend com o YouTube ocorrerá no servidor e não será uma chamada feita pelo JavaScript do navegador.

## Contrato HTTP

### `GET /api/youtube/metadata?url={url-encoded}`

Resolve a URL e retorna somente metadados sem expor a URL temporária do YouTube:

```json
{
  "videoId": "dQw4w9WgXcQ",
  "title": "...",
  "mode": "progressive",
  "contentType": "video/mp4",
  "durationMs": 123000
}
```

O Angular chama este endpoint antes de definir o `src` do elemento. A resolução poderá ocorrer novamente no endpoint de mídia; nenhum token ou estado de longa duração será exigido no browser.

### `GET /api/youtube/media?url={url-encoded}`

Entrada:

- `url`: URL absoluta de vídeo público do YouTube.
- O backend normaliza e valida o host; domínios parecidos ou esquemas que não sejam `https` serão rejeitados.
- O identificador do vídeo será extraído de `watch`, `youtu.be` e `shorts`, sem aceitar uma URL arbitrária como proxy aberto.

Resposta de sucesso:

- `200 OK` para o stream completo ou `206 Partial Content` quando houver uma requisição Range atendida.
- `Content-Type` copiado do stream escolhido, por exemplo `video/mp4`, `audio/mp4` ou `audio/webm`.
- `Content-Length`, `Content-Range` e `Accept-Ranges: bytes` quando conhecidos e suportados.
- `Content-Disposition: inline`.
- `Cache-Control: no-store`, evitando transformar o servidor em armazenamento permanente.
- `X-Karaoke-Media-Mode: progressive` ou `audio-only` para a UI.

O servidor respeitará `Range` do navegador quando o upstream e o stream selecionado permitirem. Quando o upstream não suportar Range, responderá `200` com streaming sequencial; o cliente não exibirá seek garantido nesse caso e poderá reiniciar a resolução ao detectar erro de seek. Uma requisição Range inválida receberá `416`.

Erros previstos:

- `400` para URL inválida ou host não permitido.
- `404` quando o vídeo não for localizado ou não houver stream suportado.
- `409`/`422` quando o vídeo exigir fluxo que a versão da integração não consiga reproduzir.
- `502` quando o YouTube/JavaTube falhar ao resolver ou entregar o stream.
- `504` para timeout de resolução ou de upstream.

O corpo de erro será JSON estável, com `code`, `message` e `requestId`, sem vazar URL assinada, token ou stack trace.

## Integração com JavaTube

Será criado um adapter próprio, isolando a biblioteca de terceiros:

- `YoutubeMediaResolver`: recebe uma URL validada e retorna metadados mínimos do stream selecionado.
- `YoutubeStreamSelector`: tenta primeiro o stream progressivo padrão; depois tenta o melhor áudio suportado.
- `YoutubeStreamProxy`: conecta o stream resolvido à resposta HTTP, propaga Range quando possível e fecha sempre os recursos upstream.
- `YoutubeExceptionHandler`: converte falhas da biblioteca em erros HTTP do contrato.

A dependência JavaTube será fixada em uma versão ou commit imutável, com repositório JitPack configurado no Maven apenas se essa for a forma necessária de distribuição do repositório. Não será usada uma referência móvel como `master-SNAPSHOT` em produção.

O adapter não chamará o método de download para criar arquivos permanentes. Quando a implementação do stream exigir leitura sequencial, o proxy fará streaming sob demanda e documentará a limitação de seek; o código não deverá carregar um vídeo inteiro em memória.

Como etapa obrigatória do plano, será criado um POC contra a versão/commit fixado do JavaTube para confirmar `getDefaultResolution()`, `getOnlyAudio()`, MIME, tamanho, leitura incremental e comportamento de URLs SABR/OTF. Se uma modalidade não entregar bytes sob demanda pela URL do stream, o adapter usará a rotina incremental da própria biblioteca em um executor limitado, sem materializar o vídeo inteiro em memória. A versão não será considerada pronta sem esse POC.

## Alterações no Angular

- Substituir a criação do player baseado em iframe por um elemento `HTMLVideoElement`/`HTMLAudioElement` same-origin.
- Criar um `YoutubeMediaService` que monte a URL `/api/youtube/media` com `encodeURIComponent` e controle abort/cleanup.
- Reaproveitar `MediaPlayerService` e `AudioEngineService` para eventos de carregamento, tempo, play/pause, volume e conexão com o AudioContext.
- Remover a regra que marca YouTube como `unsupported-pitch`; YouTube ficará com `canProcessPitch: true` após o elemento de mídia carregar.
- Manter o seletor de URL, mas atualizar o texto para indicar que o servidor resolve a mídia.
- Atualizar status, placeholder e testes para diferenciar `progressive` de `audio-only`.
- Retirar o carregador da YouTube IFrame API da reprodução principal e seus testes, evitando uma dependência externa no navegador.
- Se o stream for interrompido depois do início da resposta, tratar o evento como erro de mídia, exibir uma mensagem acionável e permitir uma nova resolução; não será tentado retornar JSON depois que bytes de mídia já tiverem sido enviados.

## Build e empacotamento

A estrutura continuará simples, com Angular na raiz e código Java no mesmo projeto:

```text
package.json / package-lock.json / angular.json
pom.xml
src/app/...
src/main/java/com/karaokestudio/...
src/main/resources/application.yml
```

O `pom.xml` deverá:

1. compilar Java com Spring Boot;
2. executar `npm ci` e `npm run build` em uma fase anterior a `process-resources`;
3. copiar `dist/karaoke-studio/browser/**` para `target/classes/static/`;
4. gerar o JAR executável com o plugin Spring Boot.

O `dist/` continuará sendo saída gerada e não será a fonte de runtime. O `netlify.toml` e as instruções específicas de publicação estática serão removidos ou substituídos por documentação de execução do JAR.

Comandos locais:

```powershell
nvm use 26.12.0
npm ci
mvn clean package
java -jar target/karaoke-studio-*.jar
```

O `.nvmrc` continuará fixando Node `26.12.0`; `packageManager` continuará fixando npm `11.20.0`. O projeto Java usará JDK 26, com a versão registrada na documentação e no build Maven. NVM não controla a versão do Java.

## Configuração operacional

Configurações em `application.yml`, com valores seguros para desenvolvimento:

- porta HTTP;
- timeout de resolução JavaTube;
- timeout de conexão e leitura do upstream;
- limite de URL;
- número máximo de resoluções/streams concorrentes;
- limite por IP de 10 resoluções por minuto e 2 streams simultâneos, com limite global configurável de 8 streams;
- tamanho máximo de URL de 2.048 caracteres;
- user-agent do upstream, sem credenciais no código;
- logs sem query string completa e sem URLs assinadas.

O endpoint terá timeout de resolução de 20 segundos, timeout de leitura ociosa de 30 segundos e limite de concorrência para evitar que uma aplicação pública seja usada como proxy ilimitado. Não haverá limite artificial de duração ou bytes na primeira versão, para não contrariar a exigência de vídeos públicos longos; a proteção será por concorrência, taxa, timeout e capacidade do host. O cliente HTTP seguirá redirects somente para hosts públicos esperados do ecossistema YouTube/Google Video e rejeitará destinos privados ou de rede local.

## Segurança e limites conhecidos

- A validação de host evita SSRF genérico; a aplicação não será um proxy para domínios arbitrários.
- A validação será feita tanto na URL recebida quanto no destino resolvido pelo JavaTube; IP privado, loopback, link-local e redirect para host não autorizado serão recusados.
- A resposta não expõe tokens nem URLs assinadas do YouTube.
- O backend não tenta contornar vídeos privados, DRM ou autenticação.
- O projeto deverá manter no README um aviso de que a resolução/download de conteúdo do YouTube deve ser usada somente quando houver autorização e em conformidade com os termos aplicáveis. O próprio JavaTube declara finalidade educacional e alerta para uso sem autorização.
- A aceitação de “qualquer vídeo público” significa qualquer vídeo público que o YouTube e a versão do JavaTube disponibilizem em um formato reproduzível; não significa garantia contra mudanças futuras do YouTube.

Lives, premieres, restrições regionais/etárias, incorporação bloqueada e exigências de PoToken serão tentados quando a versão configurada do JavaTube conseguir resolvê-los sem autenticação. Se o YouTube exigir credencial, DRM ou uma modalidade que a biblioteca não exponha, a aplicação retornará erro explicável; não haverá promessa de contornar essa limitação.

O backend será Spring MVC, não WebFlux, porque o JavaTube é uma integração bloqueante. O streaming usará `StreamingResponseBody` com executor limitado, cancelamento/fechamento no disconnect do cliente e timeouts de conexão/leitura. Isso mantém a implementação coerente com a API da biblioteca sem bloquear indefinidamente as threads HTTP padrão.

Para produção, a dependência será fixada em um commit imutável do JavaTube via JitPack, com verificação do POC no build. O plano deverá registrar o hash efetivamente usado e o checksum/artefato resolvido. Se o JitPack não produzir um artefato reprodutível para o commit escolhido, a alternativa será incorporar o código MIT da biblioteca em um módulo interno, preservando o mesmo adapter; uma dependência móvel não será aceita.

## Critérios de aceite

1. `nvm use 26.12.0`, `npm ci`, `npm test -- --watch=false` e `mvn clean package` executam sem erro em ambiente documentado.
2. O JAR contém `BOOT-INF/classes/static/index.html`, bundles Angular e `audio-worklets/soundtouch-processor.js`.
3. `java -jar` abre a interface Angular em `/` e qualquer rota de SPA retorna o `index.html`.
4. A mesma origem atende a UI e `/api/youtube/media`, sem configuração CORS.
5. URL válida de `youtube.com`, `youtu.be` e Shorts chega ao resolver JavaTube; host falso é rejeitado antes de qualquer acesso externo.
6. Ao carregar um vídeo público compatível, o elemento de mídia é same-origin, o áudio é conectado ao Web Audio e o ajuste de tom deixa de mostrar “não disponível para esta fonte”.
7. Play, pause, seek, volume e mudança de tom continuam sincronizados para mídia local e YouTube.
8. O fallback de áudio-only funciona e informa seu modo à UI.
9. Testes cobrem validação de URL, seleção de stream, mapeamento de erros, metadata, Range/headers, fallback SPA e empacotamento de assets.
10. A documentação explica desenvolvimento com `nvm`, build Maven, execução do JAR e limitações de disponibilidade de vídeos.

## Pergunta de aprovação

Este é o desenho que será transformado em um plano de implementação após sua aprovação. O ponto funcional principal é: preservar o vídeo quando houver stream progressivo e aceitar áudio-only como fallback para vídeos públicos que só ofereçam streams adaptativos.
