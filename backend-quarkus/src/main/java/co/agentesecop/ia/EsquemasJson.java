package co.agentesecop.ia;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.output.JsonSchemas;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deriva el esquema JSON de un tipo de salida y lo endurece.
 *
 * <p>LangChain4j genera el esquema a partir del record, pero deja {@code required} vacío.
 * Con un esquema así el modelo puede omitir cualquier campo, y eso no falla: devuelve un
 * 200 con un análisis mutilado. Observado contra la API real, Gemini rellenaba el resumen
 * ejecutivo y los riesgos, y dejaba `requisitos` vacío y `recomendacion` nulo — es decir,
 * justo lo que da valor al resultado.
 *
 * <p>Por eso se recorre el esquema y se marcan todos los campos como obligatorios. Los
 * records del dominio ya toleran listas nulas en su constructor compacto, así que exigirlo
 * todo no rompe la deserialización si el modelo aun así incumple.
 */
public final class EsquemasJson {

    private EsquemasJson() {}

    /** Devuelve el esquema del tipo con todos sus campos marcados como obligatorios. */
    public static Optional<JsonSchema> exigente(Class<?> tipo) {
        return JsonSchemas.jsonSchemaFrom(tipo).map(EsquemasJson::endurecer);
    }

    static JsonSchema endurecer(JsonSchema esquema) {
        return JsonSchema.builder()
                .name(esquema.name())
                .rootElement(exigirTodo(esquema.rootElement()))
                .build();
    }

    /** Recorre el árbol marcando como obligatoria cada propiedad de cada objeto. */
    static JsonSchemaElement exigirTodo(JsonSchemaElement elemento) {
        if (elemento instanceof JsonObjectSchema objeto) {
            Map<String, JsonSchemaElement> propiedades = new LinkedHashMap<>();
            objeto.properties()
                    .forEach((nombre, valor) -> propiedades.put(nombre, exigirTodo(valor)));

            var constructor = JsonObjectSchema.builder()
                    .description(objeto.description())
                    .addProperties(propiedades)
                    .required(List.copyOf(propiedades.keySet()));

            if (objeto.additionalProperties() != null) {
                constructor.additionalProperties(objeto.additionalProperties());
            }
            if (objeto.definitions() != null && !objeto.definitions().isEmpty()) {
                Map<String, JsonSchemaElement> definiciones = new LinkedHashMap<>();
                objeto.definitions()
                        .forEach((nombre, valor) -> definiciones.put(nombre, exigirTodo(valor)));
                constructor.definitions(definiciones);
            }
            return constructor.build();
        }

        if (elemento instanceof JsonArraySchema arreglo) {
            return JsonArraySchema.builder()
                    .description(arreglo.description())
                    .items(exigirTodo(arreglo.items()))
                    .build();
        }

        // Cadenas, números, enumeraciones y referencias se dejan intactas: no tienen
        // campos que exigir y reescribir una referencia rompería el vínculo.
        return elemento;
    }
}
