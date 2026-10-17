# POC JavaTube

## Dependência

O projeto usa o artefato JitPack `com.github.felipeucelli:JavaTube:master-f249f5fd61-1`. Esse valor é o build imutável gerado pelo JitPack a partir do commit `f249f5fd61`; não usamos `master-SNAPSHOT` no `pom.xml`.

## Contrato validado

`JavaTubeResolver` recebe uma URI pública, cria `Youtube`, consulta `streams().getDefaultResolution()` e escolhe um stream progressivo `video/mp4` somente quando ele não é SABR/OTF e expõe uma URL direta. Se essa opção não existir, consulta `getAll()` e seleciona um par de vídeo-only/áudio-only com URLs diretas, respeitando a altura máxima configurada. O par é baixado e muxado pelo backend; o navegador continua recebendo um único MP4 same-origin no `HTMLVideoElement`.

Os testes `JavaTubeResolverPocTest` e `AdaptiveStreamSelectionTest` cobrem a precedência progressiva, seleção adaptativa, preferência H.264/AAC, limites de altura, rejeição de SABR/OTF e conversão de falhas da biblioteca para `YoutubeResolutionException` sem depender de rede.

## POC de rede manual

Após configurar o JDK 26, executar a aplicação e consultar uma URL pública autorizada:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-26.0.2.101-hotspot'
java -jar target\karaoke-studio-0.1.0-SNAPSHOT.jar
```

O fluxo manual usa `POST /api/youtube/preparations` e depois `GET /api/youtube/preparations/{id}`. A validação deve registrar somente título, MIME, modo, duração, tamanho e status; nunca deve imprimir ou devolver URLs assinadas, caminhos locais ou stderr do FFmpeg.

## Limitações observadas

- Vídeos privados, autenticados, DRM ou bloqueados pelo YouTube ficam fora do contrato.
- Streams SABR/OTF não são tratados como progressivos diretos nem entram no mux. Se não houver um par direto de vídeo e áudio compatível, a preparação falha com erro sanitizado; a entrega nunca degrada silenciosamente para áudio-only.
- A biblioteca pode mudar o comportamento conforme os clientes do YouTube e os tokens exigidos pelo serviço; por isso a versão está fixada e o resolver permanece isolado.
- Streams adaptativos dependem de FFmpeg instalado no ambiente e de encoders H.264/AAC disponíveis; sem isso, o servidor mantém arquivos locais e streams progressivos funcionando, mas retorna `FFMPEG_UNAVAILABLE` para a preparação adaptativa.
