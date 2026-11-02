# Spotify Ad Restarter 2.0

Detecta anuncios de Spotify y reinicia la app **en segundo plano y con el móvil bloqueado**
(cierra con `force-stop` vía Shizuku y la reanuda con la tecla Play).

## Compilar
Sube la carpeta a GitHub (rama `main`): el workflow `.github/workflows/build.yml` genera el APK
y lo publica en *Releases*. En local: `gradle assembleRelease` (Gradle 8.13, JDK 17).

## Configuración en el móvil
1. Instala Shizuku, activa opciones de desarrollador + depuración inalámbrica e inicia Shizuku.
2. Abre la app → «1 · Acceso a notificaciones» y actívalo.
3. «2 · Permiso de Shizuku» → conceder.
4. Ajustes → Aplicaciones → Spotify Ad Restarter → Batería → «Sin restricciones».
5. Pulsa «Probar reinicio de Spotify ahora» y mira «Último resultado».
