package co.agentesecop.api;

import co.agentesecop.dominio.Analisis.RespuestaAnalisis;
import co.agentesecop.dominio.Secop.RespuestaDocumento;
import co.agentesecop.adapter.in.rest.dto.Solicitudes.SolicitudAnalisis;
import co.agentesecop.servicio.AgenteSecop;
import co.agentesecop.servicio.ExtractorDocumentos;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/** Análisis de pliegos y carga de documentos. */
@Path("/api/analisis")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "analisis", description = "Extracción de requisitos técnicos del pliego")
public class AnalisisResource {

    private final AgenteSecop agente;
    private final ExtractorDocumentos extractor;

    @Inject
    public AnalisisResource(AgenteSecop agente, ExtractorDocumentos extractor) {
        this.agente = agente;
        this.extractor = extractor;
    }

    @POST
    @Path("/requisitos")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Extrae requisitos, riesgos y alertas normativas del material")
    public RespuestaAnalisis analizar(@Valid SolicitudAnalisis solicitud) {
        return agente.analizarRequisitos(solicitud);
    }

    @POST
    @Path("/documento")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Sube un PDF, DOCX o TXT y devuelve su texto")
    public RespuestaDocumento cargarDocumento(@RestForm("archivo") FileUpload archivo)
            throws java.io.IOException {
        if (archivo == null) {
            throw new ExtractorDocumentos.DocumentoNoSoportado(
                    "No se recibió ningún archivo en el campo 'archivo'.");
        }
        byte[] contenido = java.nio.file.Files.readAllBytes(archivo.uploadedFile());
        return extractor.extraer(archivo.fileName(), contenido);
    }
}
