"use client";

/**
 * Selector de proveedor y modelo de IA.
 *
 * Los proveedores sin credenciales se listan igual, deshabilitados y con el
 * motivo a la vista: saber que Anthropic existe pero le falta la clave es más
 * útil que no verlo. El campo de modelo admite escribir uno a mano porque la
 * lista del backend es de sugerencias, no cerrada.
 */

import { useEffect, useId, useState } from "react";
import { useIA } from "@/lib/ia";
import { Aviso } from "./comunes";

const OTRO = "__otro__";

export function SelectorIA() {
  const ia = useIA();
  const idProveedor = useId();
  const idModelo = useId();
  const idPersonalizado = useId();

  const elegido = ia.proveedorElegido;
  const sugeridos = elegido?.modelos ?? [];
  const [personalizado, setPersonalizado] = useState(false);

  // Un modelo escrito a mano no está en la lista: al restaurarlo de una sesión
  // anterior hay que abrir el campo de texto o parecería que no está aplicado.
  useEffect(() => {
    if (ia.modelo && !sugeridos.includes(ia.modelo)) setPersonalizado(true);
  }, [ia.modelo, sugeridos]);

  if (ia.cargando) {
    return <p className="tenue">Consultando proveedores disponibles…</p>;
  }

  if (ia.error) {
    return (
      <Aviso tipo="alerta">
        No se pudo leer el catálogo de proveedores ({ia.error}). Se usará el
        predeterminado del servidor.{" "}
        <button className="enlace" type="button" onClick={ia.recargar}>
          Reintentar
        </button>
      </Aviso>
    );
  }

  return (
    <div className="selector-ia">
      <div>
        <label htmlFor={idProveedor}>Proveedor</label>
        <select
          id={idProveedor}
          value={ia.proveedor ?? ""}
          onChange={(e) => {
            setPersonalizado(false);
            ia.fijarProveedor(e.target.value || null);
          }}
        >
          <option value="">Predeterminado del servidor</option>
          {ia.catalogo.map((proveedor) => (
            <option
              key={proveedor.nombre}
              value={proveedor.nombre}
              disabled={!proveedor.configurado}
              title={proveedor.motivo ?? undefined}
            >
              {proveedor.etiqueta}
              {proveedor.configurado ? "" : " — sin configurar"}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor={idModelo}>Modelo</label>
        <select
          id={idModelo}
          value={personalizado ? OTRO : (ia.modelo ?? "")}
          disabled={!elegido}
          title={
            elegido
              ? undefined
              : "Elige un proveedor para escoger modelo; el servidor usa el suyo."
          }
          onChange={(e) => {
            if (e.target.value === OTRO) {
              setPersonalizado(true);
              return;
            }
            setPersonalizado(false);
            ia.fijarModelo(e.target.value || null);
          }}
        >
          <option value="">
            {elegido ? `Predeterminado (${elegido.modeloPorDefecto})` : "Predeterminado"}
          </option>
          {sugeridos.map((modelo) => (
            <option key={modelo} value={modelo}>
              {modelo}
            </option>
          ))}
          {elegido && <option value={OTRO}>Otro…</option>}
        </select>
      </div>

      {personalizado && elegido && (
        <div>
          <label htmlFor={idPersonalizado}>Identificador del modelo</label>
          <input
            id={idPersonalizado}
            type="text"
            value={ia.modelo ?? ""}
            placeholder={elegido.modeloPorDefecto}
            onChange={(e) => ia.fijarModelo(e.target.value || null)}
          />
        </div>
      )}

      {elegido?.motivo && !elegido.configurado && (
        <Aviso tipo="alerta">{elegido.motivo}</Aviso>
      )}

      {!ia.hayProveedorConfigurado && (
        <Aviso tipo="alerta">
          Ningún proveedor tiene credenciales. La búsqueda en SECOP II funciona, pero el
          análisis, la generación y la validación responderán 503.
        </Aviso>
      )}
    </div>
  );
}

/** Resumen de una línea de lo que está en uso, para la cabecera y el pie. */
export function ResumenIA() {
  const ia = useIA();
  if (ia.cargando || ia.error) return null;

  const proveedor = ia.proveedorElegido;
  if (!proveedor) return <span className="tenue">IA: predeterminada del servidor</span>;

  return (
    <span className="tenue">
      IA: {proveedor.etiqueta} / {ia.modelo ?? proveedor.modeloPorDefecto}
    </span>
  );
}
