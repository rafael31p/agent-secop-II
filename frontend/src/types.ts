/** Tipos espejo de los modelos Pydantic del backend (app/schemas.py). */

export type Criticidad = "obligatorio" | "ponderable" | "deseable" | "informativo";

export type EstadoCumplimiento =
  | "cumple"
  | "cumple_parcial"
  | "no_cumple"
  | "no_evaluable";

export type NivelRiesgo = "alto" | "medio" | "bajo";

export type Veredicto =
  | "apta"
  | "apta_con_ajustes"
  | "riesgo_de_rechazo"
  | "no_apta";

export interface FiltroProcesos {
  texto?: string | null;
  entidad?: string | null;
  departamento?: string | null;
  modalidad?: string | null;
  estado?: string | null;
  valor_min?: number | null;
  valor_max?: number | null;
  fecha_desde?: string | null;
  fecha_hasta?: string | null;
  solo_ti?: boolean;
  limite?: number;
  offset?: number;
}

export interface ProcesoResumen {
  id: string | null;
  numero_proceso: string | null;
  entidad: string | null;
  nit_entidad: string | null;
  departamento: string | null;
  ciudad: string | null;
  objeto: string | null;
  modalidad: string | null;
  estado: string | null;
  tipo_contrato: string | null;
  orden_entidad: string | null;
  adjudicado: string | null;
  valor: number | null;
  fecha_publicacion: string | null;
  fecha_ultima_publicacion: string | null;
  url: string | null;
  codigo_unspsc: string | null;
  duracion: string | null;
  score_ti: number | null;
  señales_ti: string[];
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
  categoria_ti: string;
  justificacion: string;
  encaje_proveedor: string | null;
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
  evidencia_esperada: string;
  norma_relacionada: string | null;
  cita_pliego: string | null;
}

export interface RiesgoDetectado {
  descripcion: string;
  nivel: NivelRiesgo;
  impacto: string;
  mitigacion: string;
  tipo: string;
}

export interface RespuestaAnalisis {
  resumen_ejecutivo: string;
  objeto_normalizado: string | null;
  requisitos: RequisitoTecnico[];
  riesgos: RiesgoDetectado[];
  criterios_evaluacion: string[];
  documentos_habilitantes: string[];
  preguntas_a_la_entidad: string[];
  alertas_normativas: string[];
  recomendacion: string;
}

export interface SeccionPropuesta {
  titulo: string;
  contenido: string;
  requisitos_cubiertos: string[];
}

export interface RespuestaPropuesta {
  titulo: string;
  resumen_ejecutivo: string;
  secciones: SeccionPropuesta[];
  supuestos: string[];
  vacios_de_informacion: string[];
  markdown: string;
}

export interface ItemCumplimiento {
  requisito_id: string;
  requisito: string;
  criticidad: Criticidad;
  estado: EstadoCumplimiento;
  evidencia_en_propuesta: string | null;
  brecha: string | null;
  accion_correctiva: string | null;
}

export interface RespuestaValidacion {
  puntaje_cumplimiento: number;
  veredicto: Veredicto;
  resumen: string;
  matriz: ItemCumplimiento[];
  causales_de_rechazo: string[];
  mejoras_prioritarias: string[];
}

export interface RespuestaDocumento {
  nombre_archivo: string;
  tipo: string;
  caracteres: number;
  paginas: number | null;
  texto: string;
  truncado: boolean;
}

export interface EstadoSalud {
  estado: "ok" | "degradado";
  version: string;
  proveedor_ia: string;
  ia_configurada: boolean;
  modelo: string;
  secop_dataset_procesos: string;
  secop_token_configurado: boolean;
}

export interface MensajeChat {
  rol: "user" | "assistant";
  contenido: string;
}
