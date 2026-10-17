# Karaokê Studio — Especificação para implementação no Codex

**Versão:** 1.0  
**Finalidade:** aplicação pessoal para eventos de karaokê.  
**Stack principal:** Angular + TypeScript + SCSS + Web Audio API.  
**Objetivo central:** alterar a tonalidade (pitch) do acompanhamento musical em tempo real, preservando a velocidade e a duração da música.

> **Instrução ao Codex:** implemente a aplicação de verdade, não apenas o layout. Trabalhe em fases, mantendo cada fase utilizável. Seja explícito sobre os recursos suportados e as limitações técnicas; não simule um efeito de pitch que não esteja funcionando.

---

## 1. Contexto e experiência desejada

O usuário pretende utilizar o Karaokê Studio em um computador, durante um evento com amigos. Ele quer reproduzir uma música de karaokê, visualizar o vídeo com a letra e ajustar o tom da música conforme a voz de quem vai cantar, usando botões `−` e `+` ou um slider. Alterar o tom **não pode acelerar nem desacelerar** a reprodução.

Fluxo ideal:

1. Abrir o aplicativo.
2. Selecionar a fonte: **arquivo local** ou **YouTube**.
3. Carregar a música.
4. Reproduzir e pausar usando a interface do aplicativo, quando a API da fonte permitir.
5. Ajustar o tom entre **−12 e +12 semitons**, em incrementos de 1.
6. Continuar ouvindo a música na mesma velocidade (`1,00×`).
7. Restaurar o tom original com um clique.

### 1.1. Restrição importante sobre YouTube

A integração com o YouTube deve utilizar a **YouTube IFrame Player API**, respeitando as políticas aplicáveis e a interface obrigatória do player. Essa API permite controlar reprodução, mas **não expõe a faixa de áudio como um `HTMLAudioElement` acessível à Web Audio API**. O app **não deve alegar** que consegue alterar o pitch do áudio do player oficial sem uma implementação real e permitida.

As políticas do YouTube impõem restrições à separação e modificação dos componentes de áudio/vídeo. Não implementar métodos para contorná-las, como baixar fluxos internos, interceptar endpoints privados ou ocultar elementos obrigatórios da interface incorporada. `controls=0` só deve ser usado se o comportamento resultante estiver em conformidade com as políticas e regras atuais do player.

**Estratégia adotada:**

- **Modo local:** reprodução e alteração de pitch totalmente funcionais, inclusive para vídeo MP4/WebM e áudio MP3/WAV, conforme suporte do navegador.
- **Modo YouTube:** carregamento e reprodução do vídeo por player oficial; pitch indisponível, a menos que exista abordagem expressamente permitida e validada.
- **Pesquisa opcional, isolada:** prova de conceito de captura de áudio de aba no Chrome (com permissão explícita) para conteúdo autorizado, sem assumir que isso libera o processamento de vídeos do YouTube.

A prioridade é entregar um karaokê real para arquivos locais, com arquitetura preparada para adicionar novas fontes compatíveis.

---

## 2. Requisitos funcionais

### RF-01 — Escolher fonte

Exibir duas abas ou opções:

- **Arquivo local:** escolher arquivo por seletor ou arrastar e soltar.
- **YouTube:** colar URL e carregar vídeo incorporável.

Ao trocar de fonte, pausar a reprodução, desconectar os nós antigos e liberar recursos não mais necessários.

### RF-02 — Carregamento de arquivos locais

- Suportar formatos decodificáveis pelo navegador (priorizar MP3, WAV, MP4 e WebM).
- Utilizar `URL.createObjectURL()` quando apropriado, revogando com `URL.revokeObjectURL()` ao substituir a mídia/destruir o componente.
- Mostrar nome, duração e estado da mídia.
- Manter o processamento no cliente; **não realizar upload**.
- Exibir erro amigável em caso de formato ou codec incompatível.

### RF-03 — Player

- Play/pause e reiniciar.
- Tempo atual e duração total em `mm:ss` ou `hh:mm:ss`.
- Seek por barra de progresso.
- Volume de 0% a 100% e mute.
- Tela cheia quando suportada.
- Para vídeo local, preservar imagem sincronizada ao áudio processado.
- A interface deve acompanhar `playing`, `paused`, `seeking`, `waiting`, `ended` e `error`.

### RF-04 — Pitch shifting (obrigatório para mídia local)

- Intervalo de **−12 a +12 semitons**.
- Valor inicial **0**.
- Incremento discreto de **1 semitom**.
- Botões `−` e `+`, slider e botão **Restaurar tom**.
- Aplicar alterações durante a reprodução, sem voltar ao início.
- Manter a duração e a velocidade perceptível em **1,00×**.
- Suportar áudio estéreo.
- Exibir o valor como `−3 semitons`, `Tom original` ou `+4 semitons`.
- O ajuste deve ser feito por **algoritmo de pitch shifting com preservação temporal**; **não usar somente `playbackRate`** para implementar a função.

Fator matemático de transposição:

```ts
const pitchFactor = Math.pow(2, semitons / 12);
```

Esse fator parametriza o algoritmo DSP e, isoladamente, não realiza pitch shifting.

### RF-05 — Integrar YouTube

- Aceitar URLs `youtube.com/watch?v=...`, `youtu.be/...` e, quando aplicável, `youtube.com/shorts/...`.
- Validar o ID antes de carregar.
- Inserir player com **YouTube IFrame Player API**.
- Controlar play/pause, consultar tempo e acompanhar eventos quando permitido.
- Respeitar a incorporação bloqueada por proprietário, restrições de idade e outras restrições.
- Mostrar claramente **"Ajuste de tom não disponível para esta fonte"** enquanto o áudio não for processável.
- Não sugerir que a extração do áudio seja uma etapa obrigatória ou automaticamente permitida.

### RF-06 — Captura de aba (pesquisa opcional, não bloqueia o MVP)

Investigar uma extensão **Chrome Manifest V3** com `chrome.tabCapture`, `Offscreen Document` e Web Audio API para capturar fontes autorizadas, após ação explícita do usuário. Manter a extensão separada da aplicação principal. Documentar limitações de permissão, sincronização, rota da saída e políticas da fonte. Não criar uma solução baseada em interceptação não oficial de streams do YouTube.

### RF-07 — Estados e mensagens

Mostrar estados distintos:

- Sem mídia
- Carregando
- Pronto
- Reproduzindo
- Pausado
- Processando pitch
- Fonte sem suporte a pitch
- Buffering
- Erro de reprodução
- Erro de permissão de áudio / AudioContext suspenso

Nunca exibir visualmente o efeito como ativo caso o DSP não esteja conectado à saída.

---

## 3. Exemplo de interface (referência visual)

**Estilo:** tema escuro moderno; foco no vídeo e num painel grande de pitch para uso rápido durante o evento. Layout desktop centrado (largura máxima de aproximadamente 1200 px), responsivo em tablets e celulares.

### 3.1. Wireframe

```text
┌────────────────────────────────────────────────────────────────────┐
│  🎤 KARAOKÊ STUDIO                         Sua música, seu tom.   │
├────────────────────────────────────────────────────────────────────┤
│  FONTE                                                             │
│  [ Arquivo local ] [ YouTube ]                                      │
│  [ Cole a URL do YouTube ou selecione um arquivo...       ] [Abrir]  │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                                                              │  │
│  │                         ÁREA DO VÍDEO                        │  │
│  │                    (ou capa / placeholder)                   │  │
│  │                                                              │  │
│  └──────────────────────────────────────────────────────────────┘  │
│    [▶/❚❚]  01:25  ━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━  04:10   🔊   │
├────────────────────────────────────────────────────────────────────┤
│  CONTROLE DE TONALIDADE                                            │
│  Ajuste o tom sem alterar a velocidade                              │
│                                                                    │
│                  [  −  ]      −2      [  +  ]                     │
│                             semitons                               │
│                                                                    │
│             −12  ━━━━━━━━━━━●━━━━━━━━━━━━━━  +12                   │
│                      [ Restaurar tom original ]                    │
├────────────────────────────────────────────────────────────────────┤
│  VOLUME  ━━━━━━━━━━━━━●━━━━━━  80%     VELOCIDADE  1,00×           │
│  STATUS: Processando áudio local                                   │
└────────────────────────────────────────────────────────────────────┘
```

**Comportamentos de UI:**

- No desktop, manter o player no topo e painel de tonalidade logo abaixo; permitir painel de pitch lateral se melhorar o aproveitamento da tela.
- No celular, organizar verticalmente, sem rolagem horizontal.
- Controles `−` e `+` com área de toque generosa e suporte a teclado.
- O valor do pitch deve ter fonte grande, com cor de destaque.
- Mostrar velocidade fixa `1,00×` como indicação de preservação temporal, não como promessa para fontes sem processamento.
- Desabilitar controles de pitch e explicar o motivo quando a fonte for incompatível.
- Respeitar foco visível e contraste acessível.

### 3.2. Paleta sugerida

| Token | Cor |
|---|---|
| Fundo principal | `#10141E` |
| Fundo de cards | `#1B2130` |
| Área do player | `#080B12` |
| Destaque | `#A78BFA` |
| Destaque claro | `#C4B5FD` |
| Texto principal | `#FFFFFF` |
| Texto secundário | `#9CA3AF` |
| Borda | `#374151` |

Utilizar ícones Lucide ou equivalentes, bordas arredondadas de 12–18px, transições suaves e espaçamento consistente.

### 3.3. Componentes visuais

**Header:** nome, subtítulo e indicador de processamento.  
**MediaSourceSelector:** escolha Local/YouTube e input adequado.  
**MediaPlayer:** visualização, controles, tempos e progresso.  
**PitchControl:** valor grande, botões, slider, restaurar.  
**VolumeControl:** slider e mute.  
**StatusBar:** estado do áudio, fonte e avisos de incompatibilidade.

---

## 4. Requisitos técnicos e arquitetura

### 4.1. Tecnologias

- Angular standalone, versão estável suportada no ambiente.
- TypeScript strict.
- SCSS e CSS custom properties.
- Angular Signals para estado reativo local; RxJS para integração com eventos/streams quando fizer sentido.
- Web Audio API (`AudioContext`, `MediaElementAudioSourceNode`, `GainNode`).
- `AudioWorklet` para DSP de baixa latência.
- Biblioteca de pitch shifting adequada (avaliar e justificar a escolha; SoundTouchJS pode ser investigada, mas verificar integração, licença, manutenção, qualidade e latência).
- YouTube IFrame Player API para o player oficial.

### 4.2. Fluxo de áudio local

```text
HTMLMediaElement (audio/video local)
              │
              ▼
createMediaElementSource(element)
              │
              ▼
AudioWorkletNode (pitch shifting, duração preservada)
              │
              ▼
GainNode (volume)
              │
              ▼
audioContext.destination (fones/caixas de som)
```

**Atenção:** o áudio do elemento deve seguir por **uma única rota audível**, para evitar duplicação. Criar `MediaElementAudioSourceNode` somente uma vez por elemento e gerenciar seu ciclo de vida. Manter o vídeo local no mesmo `<video>` utilizado como relógio principal e compensar eventual latência do pipeline de áudio. Ao suspender o `AudioContext`, tratar o estado e solicitar interação do usuário se necessário.

### 4.3. Serviços e interfaces

```text
src/
  app/
    core/
      models/
        audio-settings.model.ts
        player-state.model.ts
        media-source.model.ts
      services/
        audio-engine.service.ts
        pitch-shifter.service.ts
        media-player.service.ts
        youtube-player.service.ts
    features/
      karaoke/
        karaoke-page.component.ts
        karaoke-page.component.html
        karaoke-page.component.scss
    shared/
      components/
        media-source-selector/
        media-player/
        pitch-control/
        volume-control/
        status-bar/
  assets/
    audio-worklets/
      pitch-processor.js
```

Interfaces esperadas (ajustáveis):

```ts
export interface AudioSettings {
  pitchSemitones: number; // -12 a +12
  volume: number;         // 0 a 1
  muted: boolean;
}

export type MediaSourceType = 'local' | 'youtube';

export interface PlayerState {
  sourceType: MediaSourceType | null;
  isPlaying: boolean;
  isLoading: boolean;
  currentTime: number;
  duration: number;
  canProcessPitch: boolean;
  errorMessage: string | null;
}
```

Separar claramente:

1. **UI** — componentes e estado.
2. **Playback** — reprodução, seek, play/pause.
3. **DSP** — pitch, ganho, AudioWorklet.
4. **Adapters de fonte** — arquivo local versus YouTube.

### 4.4. Pitch shifter

- Processamento em tempo real para dados estéreo.
- Preservação do tempo e da duração.
- Atualização do valor de pitch durante a reprodução (por `AudioParam` ou `port.postMessage()`, conforme algoritmo).
- Evitar cliques quando houver mudanças de pitch (interpolação/smoothing quando tecnicamente apropriado).
- Fazer bypass eficiente quando `pitchSemitones === 0` sem alterar indevidamente o volume.
- Documentar latência de processamento, tamanho de buffer e possíveis artefatos.
- Preservar o relógio do elemento de mídia: **não alterar `playbackRate` como forma de transpor o tom**.

### 4.5. Gerenciamento de recursos

- Não criar `AudioContext` adicional em cada mudança do slider.
- Não registrar event listeners duplicados.
- Parar e desconectar nós ao desmontar/destruir a fonte.
- Revogar object URLs.
- Evitar loops de polling desnecessários quando os eventos nativos atenderem.
- Permitir pausar e retomar sem perda de posição.

---

## 5. Qualidade, testes e critérios de aceite

### 5.1. Testes obrigatórios

1. Carregar MP3 ou WAV local e iniciar playback.
2. Ajustar pitch `0 → +2 → −3 → 0` sem reiniciar música.
3. Confirmar que a duração e o andamento musical continuam os mesmos (1,00×).
4. Verificar se `+12` corresponde aproximadamente a uma oitava acima e `−12` a uma oitava abaixo.
5. Verificar play/pause, seek, volume, mute e finalização.
6. Carregar vídeo local e avaliar sincronização da imagem com o áudio.
7. Testar troca de mídia sem áudio duplicado nem vazamento de recursos.
8. Informar erro de codec não suportado.
9. Carregar link válido do YouTube no player oficial (quando incorporável).
10. Exibir aviso de pitch indisponível no modo YouTube, em vez de controles enganosos.
11. Verificar responsividade em desktop/tablet/celular.
12. Verificar os estados `AudioContext` suspenso e erros de permissão.

### 5.2. Critérios de aceite

- **Obrigatório:** pitch de verdade funcionando em arquivos locais, de −12 a +12 semitons.
- **Obrigatório:** a mudança de pitch **não** altera a velocidade de reprodução.
- **Obrigatório:** UI de karaokê funcional, com player, progresso, pitch e volume.
- **Obrigatório:** ausência de duplicação audível (eco).
- **Obrigatório:** indicação honesta de indisponibilidade do pitch para YouTube.
- **Desejável:** ajuste estável com baixa latência e poucos artefatos.
- **Desejável:** sincronização adequada do vídeo local.
- **Pesquisa, não requisito do MVP:** extensão de captura de aba para fontes autorizadas.

---

## 6. Plano de implementação para o Codex

**Fase 1 — Base e interface.** Criar projeto Angular, design system, layout acima, componentes standalone e estado reativo. A UI não deve conter controles falsamente marcados como funcionais.

**Fase 2 — Player local.** Carregar arquivo, reproduzir, pausar, seek, volume, tela cheia, tratar erros e liberar recursos.

**Fase 3 — Pitch real.** Escolher/implementar DSP com AudioWorklet e testar transposição temporalmente preservada de −12 a +12 semitons. Essa é a fase mais importante.

**Fase 4 — YouTube oficial.** Incorporar links por IFrame Player API, integrar estados de reprodução e bloquear controles de pitch indisponíveis.

**Fase 5 — Pesquisa opcional.** Criar prova de conceito isolada de captura de áudio da aba para conteúdo autorizado, verificando viabilidade de UX, permissões, política, eco e latência.

**Fase 6 — Refinamento.** Testes, acessibilidade, documentação, estabilidade, qualidade sonora e responsividade.

### Entregáveis

- Código-fonte completo do Angular.
- Componentes reutilizáveis e serviços desacoplados.
- Algoritmo real de alteração de pitch para arquivos locais.
- Integração com YouTube pela API oficial.
- Testes básicos automatizados e roteiro de teste manual de áudio.
- `README.md` com requisitos, instalação, scripts de execução, limitações e instruções de validação.
- Relatório curto apontando limitações técnicas conhecidas e decisões de arquitetura.

### Diretrizes finais ao Codex

Implemente em etapas verificáveis; garanta que a aplicação compile e rode a cada etapa. Priorize processamento de pitch funcionando sobre refinamentos estéticos. Não apresente uma alteração de `playbackRate` como solução de pitch sem mudança de velocidade. Não utilize extração não oficial de áudio do YouTube. Caso algum requisito não seja possível por limitação de navegador ou plataforma, implemente a alternativa viável e documente claramente a diferença.
