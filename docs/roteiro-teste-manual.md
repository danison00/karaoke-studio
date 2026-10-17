# Roteiro de teste manual

## Arquivo local

1. Abra o app em Chrome ou Edge atualizado e escolha um MP3/WAV conhecido.
2. Confirme nome, duração, status “Mídia local pronta” e indicador “DSP ativo”.
3. Inicie a reprodução e percorra `0 → +2 → −3 → 0` sem pausar nem reiniciar.
4. Confirme auditivamente a mudança de tom e que posição, duração e andamento permanecem contínuos em `1,00×`.
5. Teste `+12` (aproximadamente uma oitava acima) e `−12` (uma oitava abaixo).
6. Teste pause, retomada, seek, reinício, volume, mute e final da faixa.
7. Troque rapidamente entre dois arquivos e confirme ausência de eco ou áudio antigo.
8. Repita com MP4/WebM e observe sincronização entre letra/imagem e áudio.
9. Tente um arquivo com codec incompatível e confirme a mensagem de erro sem travamento.

## YouTube

1. Instale FFmpeg 7+ com H.264/AAC no servidor, ou defina `FFMPEG_PATH`, e inicie o JAR.
2. Selecione YouTube e carregue URLs públicas válidas dos formatos `watch`, `youtu.be` e `shorts` em Chrome desktop e Chrome Android.
3. Confirme o `POST /api/youtube/preparations`, os estados `RESOLVING`/`BUFFERING` e a transição para `STREAMING` antes do download completo.
4. Confirme que o vídeo aparece visualmente, há áudio e a playlist HLS/primeiro segmento respondem no mesmo domínio.
5. Durante a reprodução, avance para um ponto futuro: confirme `BUFFERING`/`STREAMING`, cancelamento da geração anterior e retomada a partir do ponto escolhido.
6. Volte para um ponto já cacheado e confirme que o range antigo é reutilizado; faça vários seeks rápidos e confirme que o último movimento vence.
7. Teste pitch `0 → +0,5 → −1,5 → 0` no mesmo player e confirme que a imagem permanece sincronizada e `playbackRate` continua 1.
8. Interrompa/reinicie o backend durante uma sessão e confirme erro recuperável/limpeza sem processo FFmpeg órfão.
9. Teste URL de host falso, ID inválido, HTTP, porta não padrão e vídeo privado/removido; confirme respostas de erro acionáveis.

## Deploy do JAR

1. Execute `nvm use 26.12.0; mvn clean package` com Java 26.
2. Execute `java -jar target\karaoke-studio-0.1.0-SNAPSHOT.jar` e abra a porta configurada.
3. Confirme `/` com shell Angular, `/audio-worklets/soundtouch-processor.js` com JavaScript e uma rota de asset com `200`.
4. Confirme `/api/youtube/preparations` com URL inválida (`400`) e um UUID inexistente (`404`).
5. Repita os fluxos local e YouTube no mesmo domínio, incluindo pitch fracionário e troca rápida de fonte.

## Navegador e layout

1. Recarregue a página e confirme que nenhum AudioContext é criado repetidamente ao mover sliders.
2. Caso o navegador bloqueie áudio, clique em reproduzir novamente e confira a mensagem acionável.
3. Teste larguras aproximadas de 1200, 768 e 375 px; não deve haver rolagem horizontal.
4. Percorra todos os controles com Tab e confirme foco visível, rótulos e operação por teclado.
