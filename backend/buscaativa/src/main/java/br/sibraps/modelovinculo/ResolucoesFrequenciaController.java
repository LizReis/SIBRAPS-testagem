package br.sibraps.modelovinculo;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.pet.buscaativa.services.impl.ResolucaoFrequenciaLocalService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(EscritoresVinculoController.RAIZ+"/pacientes/{paciente}/vinculos/{vinculo}/resolucoes-frequencia")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
@RequiredArgsConstructor
public class ResolucoesFrequenciaController {
    private final ResolucaoFrequenciaLocalService servico;

    public record Resolver(@NotNull ResolucaoFrequenciaLocalService.Referencia valido,
            @NotNull ResolucaoFrequenciaLocalService.Referencia desconsiderado,String justificativa,UUID ticket) {}
    public record Desfazer(String justificativa) {}
    public record Resultado(UUID id) {}

    @GetMapping("/candidatos")
    public ResponseEntity<ResolucaoFrequenciaLocalService.Candidatos> candidatos(@PathVariable UUID paciente,
            @PathVariable UUID vinculo,@RequestParam(defaultValue="0") int pagina,
            @RequestParam(defaultValue="20") int tamanho) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.candidatos(paciente,vinculo,pagina,tamanho));
    }
    @GetMapping
    public ResponseEntity<ResolucaoFrequenciaLocalService.Auditoria> auditoria(@PathVariable UUID paciente,
            @PathVariable UUID vinculo,@RequestParam(defaultValue="0") int pagina,
            @RequestParam(defaultValue="20") int tamanho) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.auditoria(paciente,vinculo,pagina,tamanho));
    }
    @PostMapping
    public ResponseEntity<Resultado> resolver(@PathVariable UUID paciente,@PathVariable UUID vinculo,
            @RequestParam Integer version,@RequestBody @Valid Resolver dados) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new Resultado(servico.resolver(paciente,vinculo,version,dados.valido(),dados.desconsiderado(),dados.justificativa(),dados.ticket())));
    }
    @PostMapping("/{resolucao}/desfazer")
    public ResponseEntity<Resultado> desfazer(@PathVariable UUID paciente,@PathVariable UUID vinculo,
            @PathVariable UUID resolucao,@RequestParam Integer version,@RequestBody @Valid Desfazer dados) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new Resultado(servico.desfazer(paciente,vinculo,version,resolucao,dados.justificativa())));
    }
}
