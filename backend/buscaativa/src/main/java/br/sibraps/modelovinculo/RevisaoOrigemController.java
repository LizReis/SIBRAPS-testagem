package br.sibraps.modelovinculo;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import com.pet.buscaativa.services.impl.RevisaoOrigemLocalService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(EscritoresVinculoController.RAIZ+"/pacientes/{paciente}/vinculos/{vinculo}/revisoes")
@PreAuthorize("hasRole('ADMINISTRADOR')")
@RequiredArgsConstructor
public class RevisaoOrigemController {
    private final RevisaoOrigemLocalService servico;
    public record Comando(String justificativa) {}
    public record Resultado(UUID id) {}

    @PostMapping("/{ticket}/origem/aplicar")
    public ResponseEntity<Resultado> aplicar(@PathVariable UUID paciente,@PathVariable UUID vinculo,
            @PathVariable UUID ticket,@RequestParam Integer version,@RequestBody @Valid Comando dados) {
        return criado(servico.aplicar(paciente,vinculo,version,ticket,dados.justificativa()));
    }
    @PostMapping("/{ticket}/origem/reabrir")
    public ResponseEntity<Resultado> reabrir(@PathVariable UUID paciente,@PathVariable UUID vinculo,
            @PathVariable UUID ticket,@RequestParam Integer version,@RequestBody @Valid Comando dados) {
        return criado(servico.reabrir(paciente,vinculo,version,ticket,dados.justificativa()));
    }
    @PostMapping("/origem/aplicacoes/{aplicacao}/desfazer")
    public ResponseEntity<Resultado> desfazer(@PathVariable UUID paciente,@PathVariable UUID vinculo,
            @PathVariable UUID aplicacao,@RequestParam Integer version,@RequestBody @Valid Comando dados) {
        return criado(servico.desfazer(paciente,vinculo,version,aplicacao,dados.justificativa()));
    }
    @GetMapping("/{ticket}/origem/auditoria")
    public ResponseEntity<RevisaoOrigemLocalService.Auditoria> auditoria(@PathVariable UUID paciente,
            @PathVariable UUID vinculo,@PathVariable UUID ticket) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.auditoria(paciente,vinculo,ticket));
    }
    private static ResponseEntity<Resultado> criado(UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(new Resultado(id));
    }
}
