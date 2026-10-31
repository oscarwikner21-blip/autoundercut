# Auto Undercut (Fabric, Minecraft 26.3)

Unterbietet im `/ah` automatisch Angebote, die günstiger sind als deine: eigenes Angebot stornieren
und genau um 1 (einstellbar) günstiger neu einstellen.

## 1. Jar bauen lassen (einmalig, kostenlos, ca. 5 Minuten)

1. Auf github.com einloggen -> oben rechts **+ -> New repository** -> Name `autoundercut` -> **Create repository**.
2. Zip entpacken. Auf der Repo-Seite **uploading an existing file** klicken und den **Inhalt** des entpackten
   Ordners per Drag & Drop hochziehen (alle Ordner und Dateien, auch `.github`, `gradle`, `src`) -> **Commit changes**.
   - Falls der Ordner `.github` nicht mit hochgeladen wurde (versteckte Ordner): im Repo **Add file -> Create new file**,
     als Namen `.github/workflows/build.yml` eintippen und den Inhalt von `build-workflow.yml.txt` einfügen -> Commit.
3. Oben auf den Tab **Actions** gehen. Dort läuft "build". Warten, bis ein grüner Haken erscheint.
   (Läuft nichts: Actions-Tab -> grüner Knopf "I understand my workflows, go ahead and enable them".)
4. Auf den Durchlauf klicken, unten bei **Artifacts** `AutoUndercut-Mod` herunterladen und entpacken. Darin liegt die `.jar`.
   - Roter Haken? Auf den Durchlauf -> **build** klicken, die rote Fehlermeldung kopieren und an Claude schicken.

## 2. Installieren

Modrinth App -> deine Fabric-26.3-Instanz -> **Mods** -> **Fabric API** hinzufügen (falls noch nicht drin)
und die `.jar` von Auto Undercut über "Add from file" hinzufügen.

## 3. Benutzen

Der erste Start ist absichtlich ein **Testlauf**: Die Mod zeigt nur im Chat, was sie tun würde, und ändert nichts.

1. `/autoah list` zeigt die Einstellungen. Standard-Item ist `minecraft:pumpkin`.
2. `/autoah once` führt einen Durchgang aus. `/autoah on` läuft dauerhaft (alle 10 Sekunden).
3. Stimmt die Anzeige im Chat? Dann scharf schalten: `/autoah set dryrun false`.
4. Ausschalten: `/autoah off`.

Die Mod übernimmt dabei dein Fenster (/ah öffnet sich von selbst). Ideal, wenn du AFK bist.
Mit ESC kannst du einen Durchgang abbrechen; in der Pause danach kannst du Befehle tippen.

| Befehl | Wirkung |
|---|---|
| `/autoah on` / `off` / `status` / `once` | ein-, ausschalten, Status, einmal durchlaufen |
| `/autoah list` | alle Einstellungen anzeigen |
| `/autoah add` | Item in der Hand zur Liste hinzufügen (oder `/autoah add minecraft:pumpkin`) |
| `/autoah remove` | Item in der Hand entfernen |
| `/autoah set undercut 5` | um wie viel unterboten wird (Standard 1) |
| `/autoah set stacks 10` | wie viele Stacks pro Durchgang höchstens neu eingestellt werden (Standard 5) |
| `/autoah set interval 10` | Pause zwischen Durchgängen in Sekunden |
| `/autoah set drop 15` | Schutz: nie mehr als 15 % unter deinen bisherigen Preis gehen |
| `/autoah min 30000` | Mindestpreis für das Item in der Hand (gilt für diese Stackgröße) |
| `/autoah set dryrun true/false` | Testlauf an/aus |
| `/autoah debug` | schreibt den Inhalt des offenen Fensters in `config/autoundercut-debug.txt` |

Weitere Einstellungen (Seitenanzahl, Klick-Pause, Suchbegriffe) stehen in `config/autoundercut.json`.

## Wenn etwas nicht klappt

Bei einem Fehler schreibt die Mod `config/autoundercut-debug.txt` (im Instanz-Ordner) und meldet es im Chat.
Schick die Chat-Meldung und die Datei an Claude. Nach 5 Fehlern in Folge schaltet sich die Mod selbst aus.
