# Spotify Ad Restarter 3.0 (100 % local)

Cuando suena un anuncio de Spotify, lo cierra y lo reanuda **en segundo plano**
(también con otra app delante y con el móvil bloqueado).

- Sin Shizuku, sin accesibilidad, sin superposición, sin root, sin PC.
- Sin permiso de INTERNET: nada puede salir del móvil.
- Sin dependencias externas: solo el SDK de Android.
- Sin polling ni servicio en primer plano: reacciona a eventos de la sesión multimedia.

## Cómo funciona
1. Detecta el anuncio por los metadatos de la sesión multimedia de Spotify.
2. Pausa Spotify (deja de ser servicio en primer plano) y llama a
   `ActivityManager.killBackgroundProcesses` (permiso normal) hasta que muere.
3. Envía la tecla Play multimedia: el sistema arranca Spotify en segundo plano y reanuda.

## Compilar
Sube la carpeta a GitHub (rama `main`): `.github/workflows/build.yml` genera el APK en *Releases*.
En local: `gradle assembleRelease` (Gradle 8.13, JDK 17).

## Configuración en el móvil
1. Instala el APK. Si Android bloquea el acceso a notificaciones: Ajustes → Aplicaciones →
   Spotify Ad Restarter → ⋮ → «Permitir ajustes restringidos».
2. Botón «1 · Acceso a notificaciones» → activa la app.
3. Botón «2 · Batería» → Batería → «Sin restricciones».
4. Con Spotify sonando (no un anuncio), pulsa «Probar cierre y reanudación ahora» y lee el registro.
