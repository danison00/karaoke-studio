# Karaokê Studio — Arquitetura Frontend-only no Netlify

**Data:** 2026-10-17  
**Status:** aprovado para implementação  
**Substitui:** a arquitetura Angular + Spring Boot descrita no plano de 2026-10-16

## Intenção

Transformar o Karaokê Studio em uma aplicação Angular puramente estática, implantável diretamente no Netlify. O backend Java, a API `/api/app-info` e todo o processo Maven deixam de existir porque não sustentam nenhuma função necessária do produto: arquivos locais, Web Audio, SoundTouch e YouTube IFrame API já funcionam integralmente no navegador.

O resultado deve ser um repositório convencional de um único aplicativo Angular, fácil de executar com NVM/npm e fácil de conectar a um projeto Netlify por Git.

## Objetivos

- Manter todas as funções atuais do Karaokê Studio: mídia local, pitch real, volume, player, estados e YouTube oficial.
- Remover completamente `backend/`, POMs, Maven Wrapper, configuração Java e documentação do Spring Boot.
- Promover o conteúdo atual de `frontend/` para a raiz do repositório.
- Renomear o pacote e o projeto Angular de `frontend` para `karaoke-studio`.
- Criar configuração declarativa do Netlify com build, diretório publicado e fallback de SPA.
- Fixar Node.js 26.12.0 por `.nvmrc`, usado localmente e pelo Netlify.
- Manter build e testes reproduzíveis com npm e o lockfile.

## Não objetivos

- Criar Netlify Functions, API serverless, banco de dados, autenticação ou persistência remota.
- Fazer upload de arquivos locais.
- Alterar a implementação de DSP ou contornar as limitações do YouTube.
- Publicar o site ou vincular uma conta Netlify durante esta mudança; o código ficará pronto para conexão e deploy.
- Adicionar prerenderização, SSR ou extensão de captura de aba.

## Antiobjetivos

- Não deixar Java/Maven como caminho alternativo ou “legado” dentro do repositório.
- Não manter duas cópias do frontend nem uma pasta `frontend/` redundante.
- Não depender de variáveis secretas para build ou execução.
- Não adicionar cabeçalhos cross-origin estritos que impeçam o iframe oficial do YouTube.
- Não declarar que o pitch funciona no YouTube.

## Arquitetura resultante

O navegador continua sendo o único runtime da aplicação:

```text
Netlify CDN
  └── Angular SPA
       ├── arquivo local → HTMLMediaElement → SoundTouch AudioWorklet → GainNode → saída
       └── URL do YouTube → IFrame Player API oficial
```

O Netlify apenas instala dependências, executa o build Angular e distribui os arquivos estáticos. Não há runtime Java, processo de servidor nem endpoint próprio depois do deploy.

## Estrutura do repositório

Estrutura principal após a migração:

```text
/
├── .nvmrc
├── .gitignore
├── angular.json
├── netlify.toml
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.spec.json
├── src/
├── public/
│   └── audio-worklets/soundtouch-processor.js
├── docs/
└── README.md
```

Serão removidos `backend/`, `pom.xml`, `.java-version`, `.mvn/`, `frontend/` após a promoção dos arquivos e os registros internos obsoletos da execução Spring Boot. O plano de 2026-10-16 e o ledger `.superpowers/sdd/**` serão removidos para não manter instruções contraditórias. A especificação funcional original será preservada como referência de produto, junto deste design e do novo plano de migração.

## Configuração Angular e npm

- `package.json.name`: `karaoke-studio`.
- Projeto em `angular.json`: `karaoke-studio`.
- Build de produção: `npm run build`.
- Testes: `npm run test:ci`.
- Saída esperada: `dist/karaoke-studio/browser`.
- `.nvmrc`: `26.12.0`.
- `package-lock.json` permanece como fonte determinística das dependências.

Nenhum código Angular consome `/api/app-info`, portanto sua remoção não exige adapter, mock ou substituição no frontend.

## Contrato de deploy no Netlify

`netlify.toml` na raiz definirá:

- comando de build `npm run build`;
- diretório publicado `dist/karaoke-studio/browser`;
- Node `26.12.0`, coerente com `.nvmrc`;
- redirecionamento `/* → /index.html` com status `200` para navegação de SPA;
- cabeçalho `X-Content-Type-Options: nosniff` para o site.

O processor SoundTouch continuará em `public/audio-worklets/soundtouch-processor.js` e deverá ser publicado em `/audio-worklets/soundtouch-processor.js` com JavaScript válido. A política não usará COEP/COOP estritos, pois o produto incorpora o player oficial do YouTube.

A documentação oficial do Netlify confirma que `.nvmrc` na base do site fixa a versão de Node e sobrepõe a configuração da interface. O Netlify instala dependências npm antes do comando de build; não será necessário incorporar `npm ci` ao script de build da aplicação.

## Documentação

O `README.md` passará a descrever somente:

- `nvm use`, `npm ci`, `npm start`, testes e build;
- conexão do repositório ao Netlify;
- build command e publish directory, embora também estejam no `netlify.toml`;
- limites reais de codecs, AudioContext, latência e YouTube.

`docs/arquitetura-e-limitacoes.md` removerá o backend e descreverá o Netlify/CDN. O roteiro manual continuará válido, com uma etapa adicional que verifica o site no domínio de preview/produção e a disponibilidade do AudioWorklet.

## Migração e segurança dos arquivos

A promoção do Angular para a raiz será feita como movimentação, não como cópia permanente. Antes de remover `frontend/`, a implementação deverá verificar que todos os arquivos versionáveis esperados existem na raiz e que não há colisões não tratadas. Diretórios gerados (`node_modules`, `dist`) não serão promovidos; serão recriados por `npm ci` e `npm run build`.

A remoção do backend é intencional e autorizada. Ela abrange código, testes, artefatos gerados, Maven Wrapper e configurações específicas de Java/Maven.

## Critérios de aceite

- Não existe código ou ferramenta Java/Maven no repositório final.
- `npm ci`, `npm run test:ci` e `npm run build` funcionam na raiz com Node 26.12.0.
- A suíte mantém todos os testes Angular existentes aprovados.
- O build produz `dist/karaoke-studio/browser/index.html` e o processor SoundTouch.
- `netlify.toml` aponta exatamente para essa saída e contém fallback de SPA.
- Uma verificação local do diretório publicado confirma `index.html`, chunks e `/audio-worklets/soundtouch-processor.js`.
- Busca textual não encontra referências operacionais a Spring Boot, Java, Maven, `/api/app-info`, `backend/` ou `localhost:8080` na documentação ativa.

## Decisões deixadas ao implementador

- A ordem mecânica exata das movimentações, desde que preserve conteúdo e evite duplicação.
- Pequenos ajustes de formatação da documentação.
- Uso de comandos nativos de movimentação ou patches, respeitando as regras de segurança do workspace.

## Decisões assumidas nesta proposta

- Promover o Angular à raiz, em vez de manter uma subpasta `frontend/`.
- Renomear o identificador Angular para `karaoke-studio` para alinhar pacote, saída e produto.
- Usar deploy contínuo por Git como caminho principal; a CLI do Netlify permanece opcional.
- Não criar funções serverless porque não existe requisito funcional para elas.
- Não inicializar um repositório Git nem criar histórico automaticamente; a migração prepara os arquivos e documenta como conectar o diretório a um repositório/Netlify posteriormente.
