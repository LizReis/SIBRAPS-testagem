package com.pet.buscaativa;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.UsuarioRepository;

@SpringBootApplication
@EnableScheduling //Necessário para atualização da lista de Unidades do BD e MotorBuscaAtiva
public class BuscaativaApplication {

	public static void main(String[] args) {
		SpringApplication.run(BuscaativaApplication.class, args);
	}

}
