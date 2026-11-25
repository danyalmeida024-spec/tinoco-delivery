# Minha Rotina (Android)

App de rotina diária: tarefas fixas (todo dia ou em dias da semana) e tarefas de uma data só,
marcadas dia após dia. Cada tarefa pode ter notificação ou despertador no horário, que funcionam
com o app fechado e o celular bloqueado.

## O que o app faz

- Tarefas fixas (todo dia, alguns dias da semana) e de uma vez só, com cor por tipo.
- Toque na tarefa para marcar como feita; arraste para o lado para trocar de dia.
- Aviso ou despertador no horário, com o app fechado. O despertador pode ser "firme":
  só desliga ao marcar Feito. A tela do despertador mostra um resumo do dia.
- Modo Foco: uma tarefa de cada vez, em tela cheia.
- Progresso: jardim que cresce com os dias seguidos, recorde e calendário do mês.
- Adicionar tarefa falando ("academia segunda, quarta e sexta às 7").
- Pular uma tarefa só num dia, sem quebrar a sequência.
- Widget na tela inicial com a próxima tarefa e botão de feito.
- Cópia de segurança em arquivo (Downloads) e restauração.
- Aba Compras: lista da semana, adicionar escrevendo ou falando, marcar no carrinho, editar e limpar comprados.

## Como instalar

1. No celular, abra a página de Releases deste repositório e baixe `MinhaRotina.apk` da versão mais nova.
2. Toque no arquivo baixado. Se o Android pedir, permita "instalar apps desta fonte".
3. Abra o app e permita notificações. Se aparecer o aviso no topo, permita também "alarmes no horário"
   e "tela cheia".
4. Use "Testar despertador" para conferir o som com a tela bloqueada.

Para atualizar, instale o APK novo por cima. Os dados ficam no celular e são mantidos.
Desinstalar o app apaga os dados.

## Como funciona

- `www/` é a tela do app (HTML, CSS e JavaScript, sem build).
- `android/app/src/main/java/app/minharotina/` tem a parte nativa:
  - `Scheduler.java` calcula o próximo horário de cada tarefa e agenda no `AlarmManager`.
    Depois que um alarme toca, a próxima vez já é agendada, então os avisos continuam mesmo sem abrir o app.
  - `AlarmReceiver.java` mostra a notificação e trata Feito, Soneca e Desligar. Também reagenda tudo
    depois de reiniciar o celular ou mudar a hora.
  - `AlarmActivity.java` é a tela cheia do despertador, por cima da tela bloqueada.
  - `RotinaPlugin.java` liga a tela (JavaScript) aos alarmes.
- O GitHub Actions (`.github/workflows/rotina-apk.yml`) gera o APK a cada mudança nesta pasta.

## Rodar localmente

```bash
npm ci
npm run sync          # copia www/ para o projeto Android
cd android && ./gradlew assembleRelease
```

A chave de assinatura (`android/app/rotina.keystore`) fica no repositório para que toda versão
instale por cima da anterior. É uma chave só para instalação manual, não para a Play Store.
