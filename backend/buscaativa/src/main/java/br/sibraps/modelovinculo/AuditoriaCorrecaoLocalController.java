package br.sibraps.modelovinculo;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.pet.buscaativa.services.impl.AuditoriaCorrecaoLocalService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(EscritoresVinculoController.RAIZ)
@PreAuthorize("hasAnyRole('ADMINISTRADOR','PROFISSIONAL')")
@RequiredArgsConstructor
public class AuditoriaCorrecaoLocalController {
    private final AuditoriaCorrecaoLocalService servico;
    @GetMapping("/agendamentos/{id}/auditoria-correcoes")
    public ResponseEntity<AuditoriaCorrecaoLocalService.Pagina> individual(@PathVariable Long id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.individual(id,page,size));
    }
    @GetMapping("/grupos/sessoes/{id}/auditoria-correcoes")
    public ResponseEntity<AuditoriaCorrecaoLocalService.Pagina> coletiva(@PathVariable Long id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(servico.coletiva(id,page,size));
    }
}
