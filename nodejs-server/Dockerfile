FROM node:22-alpine

WORKDIR /app

# Copiar package.json e instalar dependências
COPY package.json .
RUN npm install

# Copiar código da aplicação
COPY index.js .

# Expor porta
EXPOSE 8083

# Comando para iniciar o servidor
CMD ["node", "index.js"]
