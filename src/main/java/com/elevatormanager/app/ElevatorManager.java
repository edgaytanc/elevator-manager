package com.elevatormanager.app;

/**
 * Entrada mínima para verificar la compilación y ejecución del JAR en T08.
 * La interfaz gráfica y el motor se incorporarán en las tareas siguientes.
 */
public final class ElevatorManager {

    private ElevatorManager() {
        // Clase de entrada: no necesita instancias durante esta preparación.
    }

    /**
     * Comprueba que la clase de entrada puede ejecutarse desde el JAR.
     *
     * @param args argumentos de consola, reservados para futuras versiones
     */
    public static void main(String[] args) {
        System.out.println("Elevator Manager - entorno preparado.");
        System.out.println("Versión de Java: " + System.getProperty("java.version"));
        System.out.println("Etapa actual: T08 - configuración de Maven.");
    }
}
