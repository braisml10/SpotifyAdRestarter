package com.adrestarter;

interface IShell {
    // Método reservado por Shizuku para destruir el servicio
    void destroy() = 16777114;

    boolean forceStop(String pkg) = 1;
    boolean startApp(String component) = 2;
}
