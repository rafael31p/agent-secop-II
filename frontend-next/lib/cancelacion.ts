"use client";

import { useCallback, useEffect, useRef } from "react";

/**
 * Cancelación de la operación larga en curso.
 *
 * <p>Resuelve tres cosas que hay que acordarse de hacer en cada vista y que se
 * olvidan: dar al usuario forma de abandonar una llamada que tarda minutos,
 * abortar la anterior si lanza otra sin esperar, y cortar la pendiente al salir
 * de la vista —si no, sigue consumiendo cuota y termina escribiendo sobre un
 * componente desmontado—.
 *
 * <p>Es deliberadamente mínimo. El `useAsyncAction` del punto 4.2 del plan
 * absorberá además el estado de carga, el error y el `aria-busy`; mientras
 * tanto, esto cubre lo que la fase 1 necesita sin adelantar esa reforma.
 */
export function useCancelacion() {
  const controlador = useRef<AbortController | null>(null);

  useEffect(() => () => controlador.current?.abort(), []);

  /** Abre una operación nueva y cancela la anterior si seguía viva. */
  const iniciar = useCallback(() => {
    controlador.current?.abort();
    controlador.current = new AbortController();
    return controlador.current.signal;
  }, []);

  const cancelar = useCallback(() => controlador.current?.abort(), []);

  return { iniciar, cancelar };
}

/** Distingue «el usuario canceló» de «algo falló», que no se reporta igual. */
export function fueCancelada(
  senal: AbortSignal | undefined,
  excepcion: unknown,
): boolean {
  return (
    senal?.aborted === true ||
    (excepcion instanceof DOMException && excepcion.name === "AbortError")
  );
}
