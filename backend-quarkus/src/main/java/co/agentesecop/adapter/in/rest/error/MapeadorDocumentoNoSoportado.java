package co.agentesecop.adapter.in.rest.error;

import co.agentesecop.adapter.out.document.ExtractorDocumentos.DocumentoNoSoportado;
import jakarta.ws.rs.ext.Provider;

/** Subió un archivo cuyo formato no se sabe leer. */
@Provider
public class MapeadorDocumentoNoSoportado extends MapeadorDeError<DocumentoNoSoportado> {

    @Override
    protected int estado() {
        return 415;
    }
}
