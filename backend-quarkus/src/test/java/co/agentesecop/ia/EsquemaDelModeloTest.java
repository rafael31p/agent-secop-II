package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.domain.model.tender.AnalisisDePliego;
import co.agentesecop.domain.model.proposal.InformeDeCumplimiento;
import co.agentesecop.adapter.out.llm.Prompts;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fija los valores de enumeración que se le imponen al modelo en el esquema JSON.
 *
 * <h2>Por qué existe</h2>
 *
 * <p>LangChain4j deriva el esquema del <strong>nombre de la constante</strong>, no del
 * código de serialización. Hoy coinciden —{@code OBLIGATORIO} y {@code "obligatorio"}—,
 * y por eso el esquema concuerda con lo que los prompts le piden al modelo en español.
 *
 * <p>Esa coincidencia es un acoplamiento que no estaba escrito en ninguna parte y que no
 * cubría ninguna prueba. Renombrar las constantes a inglés —lo que la fase 6 del plan
 * hará— dejaría el esquema diciendo {@code MANDATORY} mientras el prompt sigue pidiendo
 * {@code "obligatorio"}. Con un esquema estricto el modelo obedece al esquema, así que el
 * prompt pasaría a mentirle sin que nada fallara de forma visible.
 *
 * <p>Esta prueba convierte ese acoplamiento invisible en uno que rompe la compilación. El
 * día que se separe el tipo de carga útil del adaptador de modelos —paso previsto en
 * SPEC-BE-01 §3.3—, el renombrado deja de ser peligroso y esta prueba se muda con él.
 */
class EsquemaDelModeloTest {

    @Test
    @DisplayName("El esquema ofrece al modelo los mismos códigos que le pide el prompt")
    void criticidadCoincideConElPrompt() {
        List<String> valores = valoresDeEnumeracion(
                AnalisisDePliego.class, "requisitos", "criticidad");

        assertEquals(List.of("OBLIGATORIO", "PONDERABLE", "DESEABLE", "INFORMATIVO"), valores,
                "Si esto cambia, el esquema y el prompt dejan de decir lo mismo.");

        // Y el prompt, por su lado, sigue pidiendo exactamente esos códigos.
        for (String valor : valores) {
            assertTrue(Prompts.INSTRUCCION_ANALISIS.contains("\"" + valor.toLowerCase() + "\""),
                    "El prompt de análisis ya no menciona «" + valor.toLowerCase()
                            + "»: revisa que el esquema y el prompt sigan alineados.");
        }
    }

    @Test
    @DisplayName("Los estados de cumplimiento del esquema son los del prompt de validación")
    void estadoCumplimientoCoincideConElPrompt() {
        List<String> valores = valoresDeEnumeracion(
                InformeDeCumplimiento.class, "matriz", "estado");

        assertEquals(List.of("CUMPLE", "CUMPLE_PARCIAL", "NO_CUMPLE", "NO_EVALUABLE"), valores);

        for (String valor : valores) {
            assertTrue(Prompts.INSTRUCCION_VALIDACION.contains("\"" + valor.toLowerCase() + "\""),
                    "El prompt de validación ya no menciona «" + valor.toLowerCase() + "».");
        }
    }

    /** Saca los valores de una enumeración anidada en la lista {@code lista} del tipo. */
    private static List<String> valoresDeEnumeracion(
            Class<?> tipo, String lista, String campo) {
        var raiz = (JsonObjectSchema) EsquemasJson.exigente(tipo).orElseThrow().rootElement();
        var elementos = (JsonArraySchema) raiz.properties().get(lista);
        var item = (JsonObjectSchema) elementos.items();
        JsonSchemaElement enumeracion = item.properties().get(campo);
        return ((JsonEnumSchema) enumeracion).enumValues();
    }
}
