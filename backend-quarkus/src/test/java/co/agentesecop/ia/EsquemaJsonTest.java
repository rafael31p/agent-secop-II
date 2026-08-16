package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.dominio.Analisis.RespuestaAnalisis;
import co.agentesecop.dominio.Propuestas.RespuestaPropuesta;
import co.agentesecop.dominio.Propuestas.RespuestaRelevancia;
import co.agentesecop.dominio.Propuestas.RespuestaValidacion;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifica el esquema JSON que se le impone al modelo.
 *
 * <p>Existe por un defecto observado contra la API real: el modelo devolvía el resumen y
 * los riesgos pero dejaba `requisitos` vacío y `recomendacion` nulo. Si el esquema no
 * marca los campos como obligatorios, el modelo es libre de omitirlos, y el resultado es
 * un análisis mutilado que parece exitoso.
 */
class EsquemaJsonTest {

    @ParameterizedTest(name = "{0} produce un esquema derivable")
    @ValueSource(classes = {
        RespuestaAnalisis.class,
        RespuestaPropuesta.class,
        RespuestaValidacion.class,
        RespuestaRelevancia.class
    })
    @DisplayName("Todos los tipos de salida se convierten a esquema")
    void esquemaDerivable(Class<?> tipo) {
        Optional<JsonSchema> esquema = EsquemasJson.exigente(tipo);

        assertTrue(esquema.isPresent(),
                "Sin esquema, el modelo responde JSON libre y la salida se degrada: "
                        + tipo.getSimpleName());
    }

    @Test
    @DisplayName("El esquema del análisis exige los campos que dan valor al resultado")
    void camposObligatoriosDelAnalisis() {
        var raiz = (JsonObjectSchema) EsquemasJson.exigente(RespuestaAnalisis.class)
                .orElseThrow()
                .rootElement();

        List<String> obligatorios = raiz.required();

        // Un análisis sin requisitos ni recomendación no sirve para nada, aunque el
        // resumen ejecutivo esté bien redactado.
        assertTrue(obligatorios.contains("requisitos"),
                "«requisitos» debe ser obligatorio. Obligatorios: " + obligatorios);
        assertTrue(obligatorios.contains("recomendacion"),
                "«recomendacion» debe ser obligatorio. Obligatorios: " + obligatorios);
        assertTrue(obligatorios.contains("resumenEjecutivo"),
                "«resumenEjecutivo» debe ser obligatorio. Obligatorios: " + obligatorios);
    }

    @Test
    @DisplayName("La lista de requisitos describe la estructura de cada elemento")
    void estructuraDeCadaRequisito() {
        var raiz = (JsonObjectSchema) EsquemasJson.exigente(RespuestaAnalisis.class)
                .orElseThrow()
                .rootElement();

        var requisitos = (JsonArraySchema) raiz.properties().get("requisitos");
        var elemento = (JsonObjectSchema) requisitos.items();

        assertTrue(elemento.properties().containsKey("id"));
        assertTrue(elemento.properties().containsKey("criticidad"));
        assertTrue(elemento.properties().containsKey("evidenciaEsperada"),
                "Propiedades: " + elemento.properties().keySet());
        assertFalse(elemento.required().isEmpty(),
                "Sin campos obligatorios, el modelo puede devolver requisitos vacíos");
    }

    @Test
    @DisplayName("La matriz de validación exige estado y criticidad por ítem")
    void estructuraDeLaMatriz() {
        var raiz = (JsonObjectSchema) EsquemasJson.exigente(RespuestaValidacion.class)
                .orElseThrow()
                .rootElement();

        assertTrue(raiz.required().contains("matriz"),
                "Obligatorios: " + raiz.required());
        var matriz = (JsonArraySchema) raiz.properties().get("matriz");
        var item = (JsonObjectSchema) matriz.items();
        assertTrue(item.properties().containsKey("estado"));
        assertTrue(item.properties().containsKey("criticidad"));
    }
}
