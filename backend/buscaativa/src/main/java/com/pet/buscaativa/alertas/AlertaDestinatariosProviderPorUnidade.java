package com.pet.buscaativa.alertas;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertaDestinatariosProviderPorUnidade implements AlertaDestinatariosProvider {

    private final UsuarioRepository usuarioRepository;
    private final PacienteRepository pacienteRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DestinatarioAlerta> montarDestinatarios() {

        return usuarioRepository.findAll()
                .stream()
                .filter(this::usuarioAssistencial)
                .filter(this::unidadeConfigurada)
                .map(usuario -> new DestinatarioAlerta(
                        usuario.getNome(),
                        usuario.getEmail(),
                        buscarPacientesDaUnidade(usuario)
                ))
                .toList();
    }

    private boolean usuarioAssistencial(Usuario usuario) {
        return usuario.getTipoUsuario() == TipoUsuario.ADMINISTRADOR
                || usuario.getTipoUsuario() == TipoUsuario.PROFISSIONAL;
    }

    private boolean unidadeConfigurada(Usuario usuario) {

        if (usuario.getUnidadeAtuacao() == null) {
            log.warn(
                    "RF26: usuário '{}' sem unidade de atuação configurada. Ignorando alerta.",
                    usuario.getEmail()
            );
            return false;
        }

        if (usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF
                && (usuario.getUsfAtuacao() == null
                || usuario.getUsfAtuacao().getId() == null)) {

            log.warn(
                    "RF26: usuário '{}' pertence a USF, mas a USF específica não está configurada. Ignorando alerta.",
                    usuario.getEmail()
            );

            return false;
        }

        return true;
    }

    private List<PacienteVermelhoDTO> buscarPacientesDaUnidade(Usuario usuario) {

        List<Paciente> pacientes;

        if (usuario.getUnidadeAtuacao() == UnidadeAtuacao.USF) {

            pacientes =
                    pacienteRepository
                            .findByStatusPacienteAndClassificacaoRiscoAndUsfReferencia_Id(
                                    StatusPaciente.ATIVO,
                                    ClassificacaoRisco.VERMELHO,
                                    usuario.getUsfAtuacao().getId()
                            );

        } else {

            CapsEnum caps = CapsEnum.valueOf(
                    usuario.getUnidadeAtuacao().name()
            );

            pacientes =
                    pacienteRepository
                            .findByStatusPacienteAndClassificacaoRiscoAndCapsReferencia(
                                    StatusPaciente.ATIVO,
                                    ClassificacaoRisco.VERMELHO,
                                    caps
                            );
        }

        return pacientes.stream()
                .map(this::paraDTO)
                .toList();
    }

    private PacienteVermelhoDTO paraDTO(Paciente paciente) {
        return new PacienteVermelhoDTO(
                paciente.getNome(),
                paciente.getCountFaltas(),
                paciente.getDataUltimaPresenca()
        );
    }
}