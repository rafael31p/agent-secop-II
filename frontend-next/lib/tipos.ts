/**
 * Espejo del contrato HTTP del backend Quarkus (`co.agentesecop.dominio`).
 *
 * Ojo al migrar código de la versión anterior: el backend Python serializaba en
 * `snake_case` y este responde en `camelCase`. Los nombres de aquí son los que
 * viajan por el cable; no hay conversión en el cliente.
 */

export type Criticidad = "obligatorio" | "ponderable" | "deseable" | "informativo";

export type EstadoCumplimiento =
  "cumple" | "cumple_parcial" | "no_cumple" | "no_evaluable";

export type NivelRiesgo = "alto" | "medio" | "bajo";

export type TipoRiesgo =
  "tecnico" | "juridico" | "financiero" | "operativo" | "cronograma" | "competencia";

export type Veredicto = "apta" | "apta_con_ajustes" | "riesgo_de_rechazo" | "no_apta";

/**
 * Proveedor y modelo elegidos para una petición. Ambos opcionales: si van nulos,
 * el servidor usa su configuración por defecto.
 */
export interface SeleccionIA {
  proveedor?: string | null;
  modelo?: string | null;
}

export interface FiltroProcesos {
  texto?: string | null;
  entidad?: string | null;
  departamento?: string | null;
  modalidad?: string | null;
  estado?: string | null;
  valorMin?: number | null;
  valorMax?: number | null;
  fechaDesde?: string | null;
  fechaHasta?: string | null;
  soloTi?: boolean;
  limite?: number;
  offset?: number;
}

export interface ProcesoResumen {
  id: string | null;
  numeroProceso: string | null;
  entidad: string | null;
  nitEntidad: string | null;
  departamento: string | null;
  ciudad: string | null;
  objeto: string | null;
  modalidad: string | null;
  estado: string | null;
  tipoContrato: string | null;
  ordenEntidad: string | null;
  adjudicado: string | null;
  valor: number | null;
  fechaPublicacion: string | null;
  fechaUltimaPublicacion: string | null;
  url: string | null;
  codigoUnspsc: string | null;
  duracion: string | null;
  scoreTi: number | null;
  senalesTi: string[];
}

export interface RespuestaProcesos {
  total: number;
  procesos: ProcesoResumen[];
  dataset: string;
  advertencias: string[];
}

export interface ProcesoPriorizado {
  id: string | null;
  objeto: string | null;
  entidad: string | null;
  valor: number | null;
  puntaje: number;
  categoriaTi: string;
  justificacion: string;
  encajeProveedor: string | null;
  banderas: string[];
}

export interface RespuestaRelevancia {
  priorizados: ProcesoPriorizado[];
  resumen: string;
}

export interface RequisitoTecnico {
  id: string;
  categoria: string;
  requisito: string;
  criticidad: Criticidad;
  evidenciaEsperada: string;
  normaRelacionada: string | null;
  citaPliego: string | null;
}

export interface RiesgoDetectado {
  descripcion: string;
  nivel: NivelRiesgo;
  impacto: string;
  mitigacion: string;
  tipo: TipoRiesgo;
}

export interface RespuestaAnalisis {
  resumenEjecutivo: string;
  objetoNormalizado: string | null;
  requisitos: RequisitoTecnico[];
  riesgos: RiesgoDetectado[];
  criteriosEvaluacion: string[];
  documentosHabilitantes: string[];
  preguntasALaEntidad: string[];
  alertasNormativas: string[];
  recomendacion: string;
}

export interface SeccionPropuesta {
  titulo: string;
  contenido: string;
  requisitosCubiertos: string[];
}

export interface RespuestaPropuesta {
  titulo: string;
  resumenEjecutivo: string;
  secciones: SeccionPropuesta[];
  supuestos: string[];
  vaciosDeInformacion: string[];
  markdown: string;
}

export interface ItemCumplimiento {
  requisitoId: string;
  requisito: string;
  criticidad: Criticidad;
  estado: EstadoCumplimiento;
  evidenciaEnPropuesta: string | null;
  brecha: string | null;
  accionCorrectiva: string | null;
}

export interface RespuestaValidacion {
  puntajeCumplimiento: number;
  veredicto: Veredicto;
  resumen: string;
  matriz: ItemCumplimiento[];
  causalesDeRechazo: string[];
  mejorasPrioritarias: string[];
}

export interface RespuestaDocumento {
  nombreArchivo: string;
  tipo: string;
  caracteres: number;
  paginas: number | null;
  texto: string;
  truncado: boolean;
}

export interface EstadoSalud {
  estado: "ok" | "degradado";
  version: string;
  proveedorIaPorDefecto: string;
  modeloPorDefecto: string;
  proveedoresConfigurados: string[];
  iaConfigurada: boolean;
  secopDatasetProcesos: string;
  secopTokenConfigurado: boolean;
}

/** Descriptor con el que se arma el selector de proveedor y modelo. */
export interface ProveedorDisponible {
  nombre: string;
  etiqueta: string;
  configurado: boolean;
  /** Sugerencias, no una lista cerrada: el backend acepta cualquier identificador. */
  modelos: string[];
  modeloPorDefecto: string;
  /** Por qué no está disponible, cuando `configurado` es falso. */
  motivo: string | null;
}

export interface MensajeChat {
  rol: "user" | "assistant";
  contenido: string;
}
