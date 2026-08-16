package co.agentesecop.domain.model.tender;

/**
 * Requisito técnico atómico y verificable extraído del pliego.
 *
 * <p>Sin anotaciones: el contrato HTTP y su documentación viven en
 * {@code adapter.in.rest.dto.AnalisisDto}, no aquí.
 *
 * <p>Los nombres de los campos siguen en español y no es por inercia. Este record es
 * además el tipo al que se vincula la respuesta del modelo, y LangChain4j deriva el
 * esquema JSON de los nombres de campo; los prompts piden {@code resumenEjecutivo},
 * {@code citaPliego}… Renombrarlos dejaría el esquema y el prompt diciendo cosas
 * distintas, en silencio. El renombrado espera a que el adaptador de salida tenga su
 * propio tipo de carga útil (SPEC-BE-01 §3.3); {@code EsquemaDelModeloTest} monta guardia
 * mientras tanto.
 */
public record RequisitoTecnico(
        String id,
        String categoria,
        String requisito,
        Criticidad criticidad,
        String evidenciaEsperada,
        String normaRelacionada,
        String citaPliego) {

    /** Regla de negocio: solo lo obligatorio puede causar rechazo. */
    public boolean puedeCausarRechazo() {
        return criticidad != null && criticidad.puedeCausarRechazo();
    }
}
