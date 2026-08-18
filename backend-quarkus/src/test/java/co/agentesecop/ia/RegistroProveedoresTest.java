package co.agentesecop.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.agentesecop.PerfilSinCredenciales;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica el descubrimiento y la resolución de proveedores.
 *
 * <p>En el perfil de pruebas ninguno tiene credenciales, lo que permite comprobar la
 * ruta de «no configurado» sin llamar a ningún servicio externo.
 */
@QuarkusTest
@TestProfile(PerfilSinCredenciales.class)
class RegistroProveedoresTest {

    @Inject
    RegistroProveedores registro;

    @Test
    @DisplayName("CDI descubre los cinco proveedores")
    void descubreTodosLosProveedores() {
        List<String> nombres = registro.catalogo().stream()
                .map(co.agentesecop.adapter.in.rest.dto.ProveedorDisponible::nombre)
                .toList();

        assertTrue(nombres.containsAll(
                List.of("gemini", "openai", "anthropic", "deepseek", "ollama")),
                "Faltan proveedores: " + nombres);
        assertEquals(5, nombres.size(), "Proveedores inesperados: " + nombres);
    }

    @Test
    @DisplayName("Cada proveedor declara modelos sugeridos y uno por defecto")
    void catalogoCompleto() {
        for (var proveedor : registro.catalogo()) {
            assertNotNull(proveedor.etiqueta(), proveedor.nombre() + " sin etiqueta");
            assertFalse(proveedor.modelos().isEmpty(),
                    proveedor.nombre() + " sin modelos sugeridos");
            assertNotNull(proveedor.modeloPorDefecto(),
                    proveedor.nombre() + " sin modelo por defecto");
        }
    }

    @Test
    @DisplayName("Un proveedor sin credenciales explica cómo obtenerlas")
    void motivoAccionable() {
        var gemini = registro.catalogo().stream()
                .filter(p -> p.nombre().equals("gemini"))
                .findFirst()
                .orElseThrow();

        assertFalse(gemini.configurado(), "En pruebas no debe haber clave");
        assertNotNull(gemini.motivo());
        assertTrue(gemini.motivo().contains("aistudio.google.com"),
                "El motivo debe decir dónde obtener la clave: " + gemini.motivo());
    }

    @Test
    @DisplayName("Un proveedor desconocido se distingue por tipo y lista los válidos")
    void proveedorDesconocido() {
        // El tipo es la afirmación; el 400 lo pone su mapeador, que es quien sabe de HTTP.
        var error = assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorDesconocido.class,
                () -> registro.resolver("proveedor-inventado"));

        assertTrue(error.getMessage().contains("gemini"),
                "Debe listar los disponibles: " + error.getMessage());
    }

    @Test
    @DisplayName("Un proveedor sin configurar tiene su propio tipo, no un fallo genérico")
    void proveedorNoConfigurado() {
        var error = assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado.class,
                () -> registro.resolver("gemini"));

        assertTrue(error.getMessage().contains("GEMINI_API_KEY"),
                "El motivo debe decir qué falta: " + error.getMessage());
    }

    @Test
    @DisplayName("Un nombre de proveedor con salto de línea no llega al mensaje")
    void noSePuedeInyectarPorElNombreDelProveedor() {
        // El nombre viene del cuerpo de la petición y acaba en el registro y en la
        // respuesta. Un salto de línea sin filtrar permite fabricar una entrada de
        // auditoría entera en un archivo de texto plano.
        var error = assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorDesconocido.class,
                () -> registro.resolver("gemini\n2026-01-01 INFO Sesion de administrador"));

        assertFalse(error.getMessage().contains("\n"),
                "El mensaje arrastra el salto de línea: " + error.getMessage());
    }

    @Test
    @DisplayName("Sin proveedor explícito resuelve el predeterminado")
    void usaElPredeterminado() {
        // Falla por falta de credenciales, no por proveedor desconocido: prueba que
        // resolvió el predeterminado en vez de rechazar la petición.
        assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado.class,
                () -> registro.resolver(null));
        assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado.class,
                () -> registro.resolver("   "));
    }

    @Test
    @DisplayName("El nombre del proveedor no distingue mayúsculas")
    void nombreInsensibleAMayusculas() {
        assertThrows(
                co.agentesecop.adapter.out.llm.error.ProveedorNoConfigurado.class,
                () -> registro.resolver("GEMINI"));
    }

    @Test
    @DisplayName("DeepSeek reutiliza la implementación de OpenAI con otros modelos")
    void deepseekReutilizaOpenAI() {
        var deepseek = registro.catalogo().stream()
                .filter(p -> p.nombre().equals("deepseek"))
                .findFirst()
                .orElseThrow();

        assertEquals("deepseek-chat", deepseek.modeloPorDefecto());
        assertTrue(deepseek.modelos().contains("deepseek-reasoner"));
    }
}
